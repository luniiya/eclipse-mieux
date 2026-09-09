package org.eclipse.mieux.vim;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.FindReplaceDocumentAdapter;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageGcDrawer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Caret;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISaveablePart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.texteditor.IEditorStatusLine;

/**
 * A native, modal Vim emulation for a single text editor.
 *
 * <p>
 * This is a from-scratch, non-plugin implementation: it hooks the editor's
 * {@link ITextViewer} directly via {@link ITextViewerExtension#prependVerifyKeyListener},
 * so it sits in front of the normal StyledText key handling and can either
 * consume a keystroke (Normal/Visual mode command) or let it fall through
 * unmodified (Insert mode - which is just Eclipse's ordinary editing).
 *
 * <p>
 * Yank/delete/paste ("registers" in real Vim) go through the actual system
 * clipboard ({@code DND.CLIPBOARD}, the same one Ctrl+C/Ctrl+V use) rather
 * than a private buffer, per the fork's design goal. Since the OS clipboard
 * carries no Vim register metadata, linewise vs. charwise is inferred from
 * whether the copied text ends in a line delimiter - the same heuristic a
 * human pasting Vim yanks into another app would rely on.
 *
 * <p>
 * Covers: motions h j k l 0 ^ $ w b e gg G f/F/t/T (+ ; ,), operators
 * d c y combined with a motion or doubled (dd/cc/yy), x X D C Y s S r ~,
 * u / Ctrl+R (undo/redo), i I a A o O (enter Insert), v V Ctrl+V (Visual /
 * Visual Line / Visual Block, the last via SWT's native
 * {@code StyledText.setBlockSelection}) with d/c/y/x/~ acting on the
 * selection (block-mode d/c/y/~ act per-column across the block's lines),
 * p/P (system-clipboard paste), / ? n N (search via
 * {@link FindReplaceDocumentAdapter}), and a small set of ":" ex-commands
 * (see {@link #executeExCommand}) entered through a Spotlight-style popup.
 * Count prefixes (e.g. 3dw, 5j) are supported throughout. A rounded-rectangle
 * badge in the bottom-right corner of the editor shows the current mode;
 * double-clicking it toggles a real {@link Mode#DISABLED} mode in which the
 * editor behaves exactly like stock Eclipse. Not implemented: named
 * registers (everything is the system clipboard), macros, the "." repeat
 * command, and block-mode I/A (block-insert replay across lines).
 */
public class VimMode implements VerifyKeyListener {

	// package-private so ModeBadge (same package) can reference it directly
	enum Mode {
		NORMAL, INSERT, VISUAL, VISUAL_LINE, VISUAL_BLOCK, DISABLED
	}

	private final IEditorPart editor;
	private final ITextViewer viewer;

	private Mode mode = Mode.NORMAL;
	private final StringBuilder countBuffer = new StringBuilder();
	private char pendingOperator = 0; // 'd', 'c', or 'y' while waiting for a motion
	private char pendingFind = 0; // 'f', 'F', 't', or 'T' while waiting for its target char
	private char pendingReplace = 0; // set to 'r' while waiting for the replacement char
	private char pendingG = 0; // set to 'g' while waiting for a second 'g' (gg)

	private char lastFindCmd = 0;
	private char lastFindChar = 0;

	private StringBuilder searchBuffer; // non-null while capturing "/" or "?" input
	private boolean searchForward = true;
	private String lastSearch;

	private int caret; // current cursor offset, authoritative while in Visual mode
	private int visualAnchor;

	// Block-vs-line caret (see updateCaretAppearance()): the widget's own
	// caret is left alone for Insert mode; Normal/Visual swap in a custom,
	// full-cell, inverted-colour block caret sized to the character underneath.
	private final StyledText styledText;
	private final Caret insertCaret;
	private Caret blockCaret;
	private Image blockCaretImage;

	// Rounded-rectangle mode indicator (bottom-right of the editor) and the
	// centred ":" command popup. Both are null if the editor has no widget
	// to attach to (defensive - viewer.getTextWidget() can theoretically
	// return null for some ITextViewer implementations).
	private final ModeBadge modeBadge;
	private final CommandPopup commandPopup;

	public VimMode(IEditorPart editor, ITextViewer viewer) {
		this.editor = editor;
		this.viewer = viewer;
		if (viewer instanceof ITextViewerExtension ext) {
			ext.prependVerifyKeyListener(this);
		}
		this.caret = safeOffset(viewer.getSelectedRange().x);
		this.styledText = viewer.getTextWidget();
		this.insertCaret = styledText != null ? styledText.getCaret() : null;
		this.modeBadge = styledText != null ? new ModeBadge(styledText, this::toggleDisabled) : null;
		this.commandPopup = styledText != null ? new CommandPopup(styledText) : null;
		updateStatusLine();
		updateCaretAppearance();
	}

	public void dispose() {
		if (viewer instanceof ITextViewerExtension ext) {
			ext.removeVerifyKeyListener(this);
		}
		if (styledText != null && !styledText.isDisposed()) {
			styledText.setCaret(insertCaret);
		}
		if (blockCaret != null && !blockCaret.isDisposed()) {
			blockCaret.dispose();
		}
		if (blockCaretImage != null && !blockCaretImage.isDisposed()) {
			blockCaretImage.dispose();
		}
		if (modeBadge != null) {
			modeBadge.dispose();
		}
		if (commandPopup != null) {
			commandPopup.dispose();
		}
	}

	/** Double-click on the mode badge: toggle Vim mode fully on/off. */
	private void toggleDisabled() {
		if (mode == Mode.DISABLED) {
			mode = Mode.NORMAL;
			caret = safeOffset(viewer.getSelectedRange().x);
		} else {
			if (mode == Mode.VISUAL_BLOCK && styledText != null && !styledText.isDisposed()) {
				styledText.setBlockSelection(false);
			}
			resetPending();
			searchBuffer = null;
			mode = Mode.DISABLED;
		}
		updateStatusLine();
		updateCaretAppearance();
	}

	// ------------------------------------------------------------------
	// Entry point
	// ------------------------------------------------------------------

	@Override
	public void verifyKey(VerifyEvent event) {
		try {
			dispatch(event);
		} catch (BadLocationException e) {
			// document changed under us (e.g. background reformat) - resync and drop the key
			caret = safeOffset(caret);
		} catch (RuntimeException e) {
			VimPlugin.log(e);
		}
		updateCaretAppearance();
	}

	private void dispatch(VerifyEvent event) throws BadLocationException {
		if (mode == Mode.DISABLED) {
			return; // vim mode is off: don't touch the event, editor behaves like stock Eclipse
		}
		if (searchBuffer != null) {
			handleSearchCapture(event);
			return;
		}
		switch (mode) {
			case INSERT -> handleInsert(event);
			case NORMAL -> handleNormal(event);
			case VISUAL, VISUAL_LINE, VISUAL_BLOCK -> handleVisual(event);
			case DISABLED -> { /* unreachable, handled above */ }
		}
	}

	// ------------------------------------------------------------------
	// Insert mode - we do almost nothing, Eclipse's own editing runs as-is
	// ------------------------------------------------------------------

	private void handleInsert(VerifyEvent event) {
		if (event.keyCode == SWT.ESC) {
			event.doit = false;
			caret = safeOffset(viewer.getSelectedRange().x);
			// real vim moves the cursor back one column when leaving Insert mode
			caret = clampToLine(caret - 1, lineOf(caret));
			enterNormal();
		}
		// anything else: let StyledText handle it natively
	}

	// ------------------------------------------------------------------
	// Normal mode
	// ------------------------------------------------------------------

	private void handleNormal(VerifyEvent event) throws BadLocationException {
		caret = safeOffset(viewer.getSelectedRange().x);
		IDocument doc = document();

		if (pendingReplace != 0) {
			event.doit = false;
			pendingReplace = 0;
			if (event.character != 0 && event.character != SWT.ESC) {
				replaceChars(doc, event.character, count());
			}
			resetPending();
			return;
		}
		if (pendingFind != 0) {
			event.doit = false;
			char target = event.character;
			char cmd = pendingFind;
			pendingFind = 0;
			if (target != 0 && target != SWT.ESC) {
				lastFindCmd = cmd;
				lastFindChar = target;
				applyFindMotion(doc, cmd, target, count(), pendingOperator);
			}
			resetPending();
			return;
		}

		if (event.keyCode == SWT.ESC) {
			event.doit = false;
			resetPending();
			return;
		}

		// let unmodified navigation keys and modified shortcuts pass through untouched,
		// except the couple of Ctrl-combos Vim itself defines.
		// NOTE: event.character under Ctrl is the raw ASCII control code (Ctrl+R -> 0x12),
		// not the letter 'r' - keyCode is the field that stays the plain letter regardless
		// of modifiers, so Ctrl-combos must be matched on keyCode, not character.
		int mods = event.stateMask & (SWT.CTRL | SWT.ALT | SWT.COMMAND);
		if (mods == SWT.CTRL && event.keyCode == 'r') {
			event.doit = false;
			doOperation(ITextOperationTarget.REDO, count());
			resetPending();
			return;
		}
		if (mods == SWT.CTRL && event.keyCode == 'v') {
			event.doit = false;
			enterVisual(Mode.VISUAL_BLOCK);
			return;
		}
		if (mods != 0) {
			return; // some other Ctrl/Alt/Cmd shortcut - not ours, let Eclipse handle it
		}
		if (isPureNavigationKey(event.keyCode)) {
			return; // arrows/Home/End/PageUp/PageDown work natively
		}

		char c = event.character;
		if (c == 0) {
			return;
		}

		event.doit = false; // we own every remaining unmodified key in Normal mode

		if (c >= '1' && c <= '9' || (c == '0' && countBuffer.length() > 0)) {
			countBuffer.append(c);
			return;
		}

		if (pendingG != 0) {
			pendingG = 0;
			if (c == 'g') {
				int line = countBuffer.length() > 0 ? count() - 1 : 0;
				moveOrOperate(doc, firstNonBlank(doc, clampLine(line)), true);
			}
			resetPending();
			return;
		}

		switch (c) {
			case 'h' -> moveOrOperate(doc, motionH(doc, count()), false);
			case 'l' -> moveOrOperate(doc, motionL(doc, count()), false);
			case 'j' -> moveOrOperate(doc, motionVert(doc, count()), true);
			case 'k' -> moveOrOperate(doc, motionVert(doc, -count()), true);
			case '0' -> moveOrOperate(doc, lineStart(doc, lineOf(caret)), false);
			case '^' -> moveOrOperate(doc, firstNonBlank(doc, lineOf(caret)), false);
			case '$' -> moveOrOperate(doc, lineEnd(doc, clampLine(lineOf(caret) + count() - 1)), true);
			case 'g' -> pendingG = 'g';
			case 'G' -> {
				int line = countBuffer.length() > 0 ? count() - 1 : doc.getNumberOfLines() - 1;
				moveOrOperate(doc, firstNonBlank(doc, clampLine(line)), true);
			}
			case 'w' -> moveOrOperate(doc, motionWordForward(doc, count(), false), false);
			case 'W' -> moveOrOperate(doc, motionWordForward(doc, count(), true), false);
			case 'b' -> moveOrOperate(doc, motionWordBackward(doc, count(), false), false);
			case 'B' -> moveOrOperate(doc, motionWordBackward(doc, count(), true), false);
			case 'e' -> moveOrOperate(doc, motionWordEnd(doc, count(), false), true);
			case 'E' -> moveOrOperate(doc, motionWordEnd(doc, count(), true), true);
			case 'f', 'F', 't', 'T' -> pendingFind = c;
			case ';' -> repeatFind(doc, false);
			case ',' -> repeatFind(doc, true);
			case 'r' -> pendingReplace = 'r';
			case '~' -> toggleCase(doc, count());
			case 'x' -> deleteChars(doc, caret, count(), false);
			case 'X' -> deleteChars(doc, caret, count(), true);
			case 'D' -> applyOperatorRange(doc, 'd', caret, lineEnd(doc, lineOf(caret)) + 1, false);
			case 'C' -> applyOperatorRange(doc, 'c', caret, lineEnd(doc, lineOf(caret)) + 1, false);
			case 'Y' -> yankLines(doc, lineOf(caret), count());
			case 's' -> { deleteChars(doc, caret, count(), false); enterInsert(caret); }
			case 'S' -> changeLines(doc, lineOf(caret), count());
			case 'u' -> doOperation(ITextOperationTarget.UNDO, count());
			case 'p' -> pasteAfter(doc);
			case 'P' -> pasteBefore(doc);
			case 'i' -> enterInsert(caret);
			case 'I' -> enterInsert(firstNonBlank(doc, lineOf(caret)));
			case 'a' -> enterInsert(Math.min(caret + 1, lineEnd(doc, lineOf(caret)) + 1));
			case 'A' -> enterInsert(lineEndExclDelim(doc, lineOf(caret)));
			case 'o' -> openLine(doc, lineOf(caret), true);
			case 'O' -> openLine(doc, lineOf(caret), false);
			case 'v' -> enterVisual(Mode.VISUAL);
			case 'V' -> enterVisual(Mode.VISUAL_LINE);
			case 'd' -> beginOperator('d');
			case 'c' -> beginOperator('c');
			case 'y' -> beginOperator('y');
			case '/' -> beginSearch(true);
			case '?' -> beginSearch(false);
			case 'n' -> repeatSearch(false);
			case 'N' -> repeatSearch(true);
			case ':' -> beginCommand();
			default -> { /* unmapped key: ignore, like real Vim does */ }
		}
	}

	private void beginOperator(char op) {
		if (pendingOperator == op) {
			// doubled operator (dd/cc/yy) => whole line(s)
			try {
				IDocument doc = document();
				int from = lineStart(doc, lineOf(caret));
				int to = lineEndInclDelim(doc, clampLine(lineOf(caret) + count() - 1));
				applyOperatorRange(doc, op, from, to, true);
			} catch (BadLocationException e) {
				VimPlugin.log(e);
			}
			resetPending();
		} else {
			pendingOperator = op;
			// keep any count typed so far; it multiplies with the motion's own count
		}
	}

	/** Called by a motion: either move the caret (no pending operator) or apply the pending operator over [caret,target). */
	private void moveOrOperate(IDocument doc, int target, boolean linewise) throws BadLocationException {
		if (pendingOperator != 0) {
			applyOperatorRange(doc, pendingOperator, caret, target, linewise);
			resetPending();
		} else {
			caret = safeOffset(target);
			setCaret(caret);
			resetPending();
		}
	}

	private void applyFindMotion(IDocument doc, char cmd, char target, int cnt, char op) throws BadLocationException {
		int result = findInLine(doc, cmd, target, cnt);
		if (result < 0) {
			return;
		}
		boolean inclusive = (cmd == 'f' || cmd == 't');
		if (op != 0) {
			int end = inclusive ? result + 1 : result;
			applyOperatorRange(doc, op, caret, end, false);
		} else {
			caret = result;
			setCaret(caret);
		}
	}

	private void repeatFind(IDocument doc, boolean reversed) throws BadLocationException {
		if (lastFindCmd == 0) {
			return;
		}
		char cmd = lastFindCmd;
		if (reversed) {
			cmd = switch (cmd) {
				case 'f' -> 'F';
				case 'F' -> 'f';
				case 't' -> 'T';
				case 'T' -> 't';
				default -> cmd;
			};
		}
		applyFindMotion(doc, cmd, lastFindChar, count(), pendingOperator);
		resetPending();
	}

	private void resetPending() {
		countBuffer.setLength(0);
		pendingOperator = 0;
		pendingFind = 0;
		pendingG = 0;
	}

	// ------------------------------------------------------------------
	// Visual mode
	// ------------------------------------------------------------------

	private void enterVisual(Mode visualMode) {
		visualAnchor = caret;
		mode = visualMode;
		if (visualMode == Mode.VISUAL_BLOCK && styledText != null && !styledText.isDisposed()) {
			styledText.setBlockSelection(true);
		}
		updateSelection();
		updateStatusLine();
	}

	private void handleVisual(VerifyEvent event) throws BadLocationException {
		IDocument doc = document();

		if (event.keyCode == SWT.ESC) {
			event.doit = false;
			setCaret(caret);
			enterNormal();
			return;
		}
		int mods = event.stateMask & (SWT.CTRL | SWT.ALT | SWT.COMMAND);
		if (mods == SWT.CTRL && event.keyCode == 'v') {
			// Ctrl+V from within Visual toggles Visual Block on/off, keeping the anchor.
			event.doit = false;
			if (mode == Mode.VISUAL_BLOCK) {
				enterNormalFromVisual();
			} else {
				mode = Mode.VISUAL_BLOCK;
				if (styledText != null && !styledText.isDisposed()) {
					styledText.setBlockSelection(true);
				}
				updateSelection();
				updateStatusLine();
			}
			return;
		}
		if (mods != 0) {
			return;
		}
		char c = event.character;
		if (c == 0) {
			// arrows/Home/End need to keep driving the Vim-managed selection - if we just
			// let them fall through, StyledText's own caret-move-without-shift behaviour
			// silently collapses whatever selection we just set up.
			int target = motionForKeyCode(doc, event.keyCode);
			if (target < 0) {
				return;
			}
			event.doit = false;
			caret = target;
			updateSelection();
			resetPending();
			return;
		}
		event.doit = false;

		if (c >= '1' && c <= '9' || (c == '0' && countBuffer.length() > 0)) {
			countBuffer.append(c);
			return;
		}

		switch (c) {
			case 'h' -> { caret = motionH(doc, count()); updateSelection(); resetPending(); }
			case 'l' -> { caret = motionL(doc, count()); updateSelection(); resetPending(); }
			case 'j' -> { caret = motionVert(doc, count()); updateSelection(); resetPending(); }
			case 'k' -> { caret = motionVert(doc, -count()); updateSelection(); resetPending(); }
			case '0' -> { caret = lineStart(doc, lineOf(caret)); updateSelection(); resetPending(); }
			case '^' -> { caret = firstNonBlank(doc, lineOf(caret)); updateSelection(); resetPending(); }
			case '$' -> { caret = lineEnd(doc, lineOf(caret)); updateSelection(); resetPending(); }
			case 'w' -> { caret = motionWordForward(doc, count(), false); updateSelection(); resetPending(); }
			case 'b' -> { caret = motionWordBackward(doc, count(), false); updateSelection(); resetPending(); }
			case 'e' -> { caret = motionWordEnd(doc, count(), false); updateSelection(); resetPending(); }
			case 'G' -> { caret = firstNonBlank(doc, doc.getNumberOfLines() - 1); updateSelection(); resetPending(); }
			case 'o' -> { int t = visualAnchor; visualAnchor = caret; caret = t; updateSelection(); }
			case 'v' -> enterNormalFromVisual();
			case 'V' -> {
				if (mode == Mode.VISUAL_BLOCK && styledText != null && !styledText.isDisposed()) {
					styledText.setBlockSelection(false);
				}
				mode = Mode.VISUAL_LINE;
				updateSelection();
				updateStatusLine();
			}
			case 'd', 'x' -> {
				if (mode == Mode.VISUAL_BLOCK) {
					deleteBlockSelection(doc);
				} else {
					deleteSelection(doc);
				}
				enterNormalFromVisual();
			}
			case 'c', 's' -> {
				if (mode == Mode.VISUAL_BLOCK) {
					int[] cols = blockColumns();
					deleteBlockSelection(doc);
					enterInsert(safeOffset(lineStart(doc, cols[0]) + cols[2]));
				} else {
					int start = selectionStart();
					deleteSelection(doc);
					enterInsert(start);
				}
			}
			case 'y' -> {
				if (mode == Mode.VISUAL_BLOCK) {
					int[] cols = blockColumns();
					yankBlockSelection(doc);
					caret = safeOffset(lineStart(doc, cols[0]) + cols[2]);
				} else {
					yankSelection(doc);
					caret = selectionStart();
				}
				enterNormalFromVisual();
			}
			case '~' -> {
				if (mode == Mode.VISUAL_BLOCK) {
					toggleCaseBlockSelection(doc);
				} else {
					toggleCaseSelection(doc);
				}
				enterNormalFromVisual();
			}
			default -> { /* ignore */ }
		}
	}

	/** Maps a non-printable navigation key to a Visual-mode motion target, or -1 if it's not one of ours. */
	private int motionForKeyCode(IDocument doc, int keyCode) {
		return switch (keyCode) {
			case SWT.ARROW_LEFT -> motionH(doc, count());
			case SWT.ARROW_RIGHT -> motionL(doc, count());
			case SWT.ARROW_UP -> motionVert(doc, -count());
			case SWT.ARROW_DOWN -> motionVert(doc, count());
			case SWT.HOME -> lineStart(doc, lineOf(caret));
			case SWT.END -> lineEnd(doc, lineOf(caret));
			default -> -1;
		};
	}

	private void enterNormalFromVisual() {
		setCaret(caret);
		enterNormal();
	}

	private void enterNormal() {
		if (mode == Mode.VISUAL_BLOCK && styledText != null && !styledText.isDisposed()) {
			styledText.setBlockSelection(false);
		}
		mode = Mode.NORMAL;
		resetPending();
		setCaret(caret);
		updateStatusLine();
	}

	private int selectionStart() {
		return mode == Mode.VISUAL_LINE ? Math.min(caret, visualAnchor) : Math.min(caret, visualAnchor);
	}

	private int selectionEndExclusive(IDocument doc) throws BadLocationException {
		if (mode == Mode.VISUAL_LINE) {
			return lineEndInclDelim(doc, lineOf(Math.max(caret, visualAnchor)));
		}
		return Math.max(caret, visualAnchor) + 1;
	}

	private int selectionStartLineAligned(IDocument doc) throws BadLocationException {
		if (mode == Mode.VISUAL_LINE) {
			return lineStart(doc, lineOf(Math.min(caret, visualAnchor)));
		}
		return selectionStart();
	}

	private void deleteSelection(IDocument doc) throws BadLocationException {
		int from = selectionStartLineAligned(doc);
		int to = selectionEndExclusive(doc);
		copyToClipboard(doc.get(from, to - from));
		doc.replace(from, to - from, "");
		caret = safeOffset(from);
	}

	private void yankSelection(IDocument doc) throws BadLocationException {
		int from = selectionStartLineAligned(doc);
		int to = selectionEndExclusive(doc);
		copyToClipboard(doc.get(from, to - from));
	}

	private void toggleCaseSelection(IDocument doc) throws BadLocationException {
		int from = selectionStartLineAligned(doc);
		int to = selectionEndExclusive(doc);
		String text = doc.get(from, to - from);
		doc.replace(from, to - from, toggleCase(text));
		caret = safeOffset(from);
	}

	// ------------------------------------------------------------------
	// Visual Block: operates per-column-range across a span of lines rather
	// than on one contiguous offset range. Column indices (not pixels) are
	// used for the actual edits so they're correct for proportional fonts
	// too; updateBlockSelection() below handles the on-screen highlight,
	// which does need real pixel bounds (SWT's own block-selection API).
	// ------------------------------------------------------------------

	/** {topLine, bottomLine, leftCol, rightCol} of the block spanned by visualAnchor..caret. */
	private int[] blockColumns() {
		IDocument doc = document();
		int lineA = lineOf(visualAnchor);
		int lineB = lineOf(caret);
		int colA = visualAnchor - lineStart(doc, lineA);
		int colB = caret - lineStart(doc, lineB);
		return new int[] {
			Math.min(lineA, lineB), Math.max(lineA, lineB),
			Math.min(colA, colB), Math.max(colA, colB)
		};
	}

	private void deleteBlockSelection(IDocument doc) throws BadLocationException {
		int[] cols = blockColumns();
		copyToClipboard(blockText(doc, cols));
		// delete back-to-front so earlier lines' offsets stay valid
		for (int line = cols[1]; line >= cols[0]; line--) {
			int ls = lineStart(doc, line);
			int lineLen = Math.max(0, lineEnd(doc, line) - ls + 1);
			int from = ls + Math.min(cols[2], lineLen);
			int to = ls + Math.min(cols[3] + 1, lineLen);
			if (to > from) {
				doc.replace(from, to - from, "");
			}
		}
		caret = safeOffset(lineStart(doc, cols[0]) + cols[2]);
	}

	private void yankBlockSelection(IDocument doc) throws BadLocationException {
		copyToClipboard(blockText(doc, blockColumns()));
	}

	private void toggleCaseBlockSelection(IDocument doc) throws BadLocationException {
		int[] cols = blockColumns();
		for (int line = cols[0]; line <= cols[1]; line++) {
			int ls = lineStart(doc, line);
			int lineLen = Math.max(0, lineEnd(doc, line) - ls + 1);
			int from = ls + Math.min(cols[2], lineLen);
			int to = ls + Math.min(cols[3] + 1, lineLen);
			if (to > from) {
				String text = doc.get(from, to - from);
				doc.replace(from, to - from, toggleCase(text));
			}
		}
		caret = safeOffset(lineStart(doc, cols[0]) + cols[2]);
	}

	private String blockText(IDocument doc, int[] cols) throws BadLocationException {
		StringBuilder sb = new StringBuilder();
		for (int line = cols[0]; line <= cols[1]; line++) {
			int ls = lineStart(doc, line);
			int lineLen = Math.max(0, lineEnd(doc, line) - ls + 1);
			int from = ls + Math.min(cols[2], lineLen);
			int to = ls + Math.min(cols[3] + 1, lineLen);
			if (to > from) {
				sb.append(doc.get(from, to - from));
			}
			sb.append('\n');
		}
		return sb.toString();
	}

	/** Average character width of the widget's current font, for the block highlight's right edge. */
	private int averageCharWidth() {
		if (styledText == null || styledText.isDisposed()) {
			return 1;
		}
		GC gc = new GC(styledText);
		try {
			return Math.max(1, (int) Math.round(gc.getFontMetrics().getAverageCharacterWidth()));
		} finally {
			gc.dispose();
		}
	}

	private void updateBlockSelection() {
		if (styledText == null || styledText.isDisposed()) {
			return;
		}
		try {
			// getLocationAtOffset() returns coordinates relative to the widget's
			// visible client area (i.e. already adjusted for scrolling), while
			// setBlockSelectionBounds() expects document-absolute coordinates
			// (its javadoc says so explicitly, and its implementation subtracts
			// the current scroll offsets right back out) - so convert by adding
			// the scroll offsets back in.
			int hScroll = styledText.getHorizontalPixel();
			int vScroll = styledText.getTopPixel();
			Point anchorPt = styledText.getLocationAtOffset(safeOffset(visualAnchor));
			Point caretPt = styledText.getLocationAtOffset(safeOffset(caret));
			int ax = anchorPt.x + hScroll;
			int ay = anchorPt.y + vScroll;
			int cx = caretPt.x + hScroll;
			int cy = caretPt.y + vScroll;
			int lineHeight = styledText.getLineHeight(safeOffset(caret));
			int left = Math.min(ax, cx);
			int right = Math.max(ax, cx) + averageCharWidth();
			int top = Math.min(ay, cy);
			int bottom = Math.max(ay, cy) + lineHeight;
			styledText.setBlockSelectionBounds(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
		} catch (IllegalArgumentException | SWTException e) {
			// offset momentarily out of sync with the widget (e.g. mid-edit) - skip this refresh
		}
	}

	private void updateSelection() {
		if (mode == Mode.VISUAL_BLOCK) {
			updateBlockSelection();
			return;
		}
		int a = visualAnchor;
		int b = caret;
		int from, to;
		if (mode == Mode.VISUAL_LINE) {
			IDocument doc = document();
			int fromLine = lineOf(Math.min(a, b));
			int toLine = lineOf(Math.max(a, b));
			from = lineStart(doc, fromLine);
			to = lineEnd(doc, toLine) + 1; // include the last char of the last line
		} else {
			from = Math.min(a, b);
			to = Math.max(a, b) + 1;
		}
		to = Math.min(to, document().getLength());
		viewer.setSelectedRange(from, Math.max(0, to - from));
	}

	// ------------------------------------------------------------------
	// Operators
	// ------------------------------------------------------------------

	private void applyOperatorRange(IDocument doc, char op, int from, int to, boolean linewise) throws BadLocationException {
		if (to < from) {
			int t = from;
			from = to;
			to = t;
		}
		if (linewise) {
			from = lineStart(doc, lineOf(from));
			to = lineEndInclDelim(doc, lineOf(Math.max(from, to - 1)));
		}
		from = safeOffset(from);
		to = Math.min(document().getLength(), Math.max(from, to));
		String text = doc.get(from, to - from);
		switch (op) {
			case 'y' -> {
				copyToClipboard(text);
				caret = safeOffset(from);
				setCaret(caret);
			}
			case 'd' -> {
				copyToClipboard(text);
				doc.replace(from, to - from, "");
				caret = safeOffset(linewise ? firstNonBlank(doc, clampLine(lineOf(from))) : from);
				setCaret(caret);
			}
			case 'c' -> {
				copyToClipboard(text);
				doc.replace(from, to - from, "");
				enterInsert(from);
			}
			default -> { /* unknown operator, ignore */ }
		}
	}

	private void deleteChars(IDocument doc, int at, int cnt, boolean before) {
		try {
			int line = lineOf(at);
			int from, to;
			if (before) {
				from = Math.max(lineStart(doc, line), at - cnt);
				to = at;
			} else {
				from = at;
				to = Math.min(lineEnd(doc, line) + 1, at + cnt);
			}
			if (to <= from) {
				return;
			}
			String text = doc.get(from, to - from);
			copyToClipboard(text);
			doc.replace(from, to - from, "");
			caret = safeOffset(clampToLine(from, line));
			setCaret(caret);
		} catch (BadLocationException e) {
			VimPlugin.log(e);
		}
	}

	private void yankLines(IDocument doc, int line, int cnt) {
		try {
			int from = lineStart(doc, line);
			int to = lineEndInclDelim(doc, clampLine(line + cnt - 1));
			copyToClipboard(doc.get(from, to - from));
		} catch (BadLocationException e) {
			VimPlugin.log(e);
		}
	}

	private void changeLines(IDocument doc, int line, int cnt) {
		try {
			int from = lineStart(doc, line);
			int to = lineEndInclDelim(doc, clampLine(line + cnt - 1));
			copyToClipboard(doc.get(from, to - from));
			doc.replace(from, to - from, "\n");
			enterInsert(from);
		} catch (BadLocationException e) {
			VimPlugin.log(e);
		}
	}

	private void replaceChars(IDocument doc, char with, int cnt) {
		try {
			int line = lineOf(caret);
			int end = Math.min(lineEnd(doc, line) + 1, caret + cnt);
			int n = end - caret;
			if (n <= 0) {
				return;
			}
			StringBuilder repl = new StringBuilder();
			for (int i = 0; i < n; i++) {
				repl.append(with);
			}
			doc.replace(caret, n, repl.toString());
			caret = safeOffset(caret + n - 1);
			setCaret(caret);
		} catch (BadLocationException e) {
			VimPlugin.log(e);
		}
	}

	private void toggleCase(IDocument doc, int cnt) {
		try {
			int line = lineOf(caret);
			int end = Math.min(lineEnd(doc, line) + 1, caret + cnt);
			if (end <= caret) {
				return;
			}
			String text = doc.get(caret, end - caret);
			doc.replace(caret, end - caret, toggleCase(text));
			caret = safeOffset(end);
			setCaret(caret);
		} catch (BadLocationException e) {
			VimPlugin.log(e);
		}
	}

	private static String toggleCase(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isUpperCase(c)) {
				sb.append(Character.toLowerCase(c));
			} else if (Character.isLowerCase(c)) {
				sb.append(Character.toUpperCase(c));
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private void openLine(IDocument doc, int line, boolean below) {
		try {
			String delim = doc.getLineDelimiter(line);
			if (delim == null) {
				delim = TextUtilities.getDefaultLineDelimiter(doc);
			}
			// Inserting a bare delimiter at the boundary carves out a new, empty
			// line right there - the insertion offset itself becomes that line's
			// (empty) start, which is exactly where 'o'/'O' should drop the caret.
			int insertAt = below ? lineEndInclDelim(doc, line) : lineStart(doc, line);
			doc.replace(insertAt, 0, delim);
			enterInsert(insertAt);
		} catch (BadLocationException e) {
			VimPlugin.log(e);
		}
	}

	// ------------------------------------------------------------------
	// Paste (system clipboard)
	// ------------------------------------------------------------------

	private void pasteAfter(IDocument doc) {
		String text = getClipboardText();
		if (text == null || text.isEmpty()) {
			return;
		}
		try {
			if (isLinewise(text)) {
				int line = lineOf(caret);
				int at = lineEndInclDelim(doc, line);
				doc.replace(at, 0, text);
				caret = safeOffset(firstNonBlank(doc, clampLine(line + 1)));
			} else {
				int at = Math.min(caret + 1, lineEnd(doc, lineOf(caret)) + 1);
				doc.replace(at, 0, text);
				caret = safeOffset(at + text.length() - 1);
			}
			setCaret(caret);
		} catch (BadLocationException e) {
			VimPlugin.log(e);
		}
	}

	private void pasteBefore(IDocument doc) {
		String text = getClipboardText();
		if (text == null || text.isEmpty()) {
			return;
		}
		try {
			if (isLinewise(text)) {
				int line = lineOf(caret);
				int at = lineStart(doc, line);
				doc.replace(at, 0, text);
				caret = safeOffset(firstNonBlank(doc, line));
			} else {
				doc.replace(caret, 0, text);
				caret = safeOffset(caret + text.length() - 1);
			}
			setCaret(caret);
		} catch (BadLocationException e) {
			VimPlugin.log(e);
		}
	}

	private static boolean isLinewise(String text) {
		return text.endsWith("\n") || text.endsWith("\r");
	}

	// ------------------------------------------------------------------
	// Search ( / ? n N ) via FindReplaceDocumentAdapter
	// ------------------------------------------------------------------

	private void beginSearch(boolean forward) {
		searchForward = forward;
		searchBuffer = new StringBuilder();
		IEditorStatusLine status = statusLine();
		if (status != null) {
			status.setMessage(false, (forward ? "/" : "?"), null);
		}
	}

	private void handleSearchCapture(VerifyEvent event) {
		event.doit = false;
		if (event.keyCode == SWT.ESC) {
			searchBuffer = null;
			updateStatusLine();
			return;
		}
		if (event.character == SWT.CR || event.character == SWT.LF) {
			lastSearch = searchBuffer.toString();
			searchBuffer = null;
			runSearch(lastSearch, searchForward);
			return;
		}
		if (event.keyCode == SWT.BS) {
			if (searchBuffer.length() > 0) {
				searchBuffer.setLength(searchBuffer.length() - 1);
			}
		} else if (event.character != 0) {
			searchBuffer.append(event.character);
		}
		IEditorStatusLine status = statusLine();
		if (status != null) {
			status.setMessage(false, (searchForward ? "/" : "?") + searchBuffer, null);
		}
	}

	private void repeatSearch(boolean reversed) {
		if (lastSearch == null) {
			return;
		}
		// 'n' repeats in the original direction, 'N' reverses it - i.e.
		// forward == XOR(reversed, searchForward). (The previous ternary
		// here computed the wrong thing for two of the four cases - e.g.
		// 'n' after a forward '/' search would search backward instead of
		// continuing forward.)
		runSearch(lastSearch, reversed != searchForward);
	}

	private void runSearch(String pattern, boolean forward) {
		if (pattern == null || pattern.isEmpty()) {
			updateStatusLine();
			return;
		}
		try {
			FindReplaceDocumentAdapter adapter = new FindReplaceDocumentAdapter(document());
			IRegion region = adapter.find(caret + (forward ? 1 : 0), pattern, forward, true, false, false);
			if (region == null) {
				// wrap around
				region = adapter.find(forward ? 0 : document().getLength(), pattern, forward, true, false, false);
			}
			if (region != null) {
				caret = region.getOffset();
				setCaret(caret);
			}
		} catch (BadLocationException | RuntimeException e) {
			VimPlugin.log(e);
		} finally {
			updateStatusLine();
		}
	}

	// ------------------------------------------------------------------
	// Motions
	// ------------------------------------------------------------------

	private int motionH(IDocument doc, int cnt) {
		return clampToLine(caret - cnt, lineOf(caret));
	}

	private int motionL(IDocument doc, int cnt) {
		return clampToLine(caret + cnt, lineOf(caret));
	}

	private int motionVert(IDocument doc, int delta) {
		try {
			int line = lineOf(caret);
			int col = caret - lineStart(doc, line);
			int target = clampLine(line + delta);
			int targetLineLen = Math.max(0, lineEnd(doc, target) - lineStart(doc, target) + 1);
			int newCol = Math.min(col, Math.max(0, targetLineLen - 1));
			return lineStart(doc, target) + newCol;
		} catch (RuntimeException e) {
			return caret;
		}
	}

	private int findInLine(IDocument doc, char cmd, char target, int cnt) throws BadLocationException {
		int line = lineOf(caret);
		int lineStart = lineStart(doc, line);
		int lineEnd = lineEnd(doc, line); // last char offset (exclusive of delimiter)
		String text = doc.get(lineStart, Math.max(0, lineEnd - lineStart + 1));
		int pos = caret - lineStart;
		int found = -1;
		if (cmd == 'f' || cmd == 't') {
			int from = pos + 1;
			for (int n = 0; n < cnt; n++) {
				int idx = text.indexOf(target, from);
				if (idx < 0) {
					return -1;
				}
				found = idx;
				from = idx + 1;
			}
			return lineStart + (cmd == 't' ? found - 1 : found);
		} else {
			int from = pos - 1;
			for (int n = 0; n < cnt; n++) {
				int idx = from < 0 ? -1 : text.lastIndexOf(target, from);
				if (idx < 0) {
					return -1;
				}
				found = idx;
				from = idx - 1;
			}
			return lineStart + (cmd == 'T' ? found + 1 : found);
		}
	}

	private static int classify(char c) {
		if (Character.isWhitespace(c)) {
			return 0;
		}
		if (Character.isLetterOrDigit(c) || c == '_') {
			return 1;
		}
		return 2;
	}

	private int motionWordForward(IDocument doc, int cnt, boolean big) {
		int pos = caret;
		int len = doc.getLength();
		for (int n = 0; n < cnt; n++) {
			if (pos >= len) {
				break;
			}
			try {
				int startClass = big ? (Character.isWhitespace(doc.getChar(pos)) ? 0 : 1) : classify(doc.getChar(pos));
				while (pos < len && sameClass(doc, pos, startClass, big) ) {
					pos++;
				}
				while (pos < len && Character.isWhitespace(doc.getChar(pos))) {
					pos++;
				}
			} catch (BadLocationException e) {
				break;
			}
		}
		return Math.min(pos, Math.max(0, len - 1));
	}

	private boolean sameClass(IDocument doc, int pos, int cls, boolean big) throws BadLocationException {
		char c = doc.getChar(pos);
		int actual = big ? (Character.isWhitespace(c) ? 0 : 1) : classify(c);
		return actual == cls && actual != 0;
	}

	private int motionWordEnd(IDocument doc, int cnt, boolean big) {
		int pos = caret;
		int len = doc.getLength();
		for (int n = 0; n < cnt; n++) {
			try {
				pos++;
				while (pos < len && Character.isWhitespace(doc.getChar(pos))) {
					pos++;
				}
				if (pos >= len) {
					pos = len - 1;
					break;
				}
				int startClass = big ? 1 : classify(doc.getChar(pos));
				while (pos + 1 < len && sameClass(doc, pos + 1, startClass, big)) {
					pos++;
				}
			} catch (BadLocationException e) {
				break;
			}
		}
		return Math.max(0, Math.min(pos, len - 1));
	}

	private int motionWordBackward(IDocument doc, int cnt, boolean big) {
		int pos = caret;
		for (int n = 0; n < cnt; n++) {
			try {
				pos--;
				while (pos >= 0 && Character.isWhitespace(doc.getChar(pos))) {
					pos--;
				}
				if (pos < 0) {
					pos = 0;
					break;
				}
				int cls = big ? 1 : classify(doc.getChar(pos));
				while (pos > 0 && sameClass(doc, pos - 1, cls, big)) {
					pos--;
				}
			} catch (BadLocationException e) {
				break;
			}
		}
		return Math.max(0, pos);
	}

	// ------------------------------------------------------------------
	// Document/line helpers
	// ------------------------------------------------------------------

	private IDocument document() {
		return viewer.getDocument();
	}

	private int lineOf(int offset) {
		try {
			return document().getLineOfOffset(safeOffset(offset));
		} catch (BadLocationException e) {
			return 0;
		}
	}

	private int clampLine(int line) {
		return Math.max(0, Math.min(line, document().getNumberOfLines() - 1));
	}

	private int lineStart(IDocument doc, int line) {
		try {
			return doc.getLineOffset(clampLine(line));
		} catch (BadLocationException e) {
			return 0;
		}
	}

	/** Offset of the last real character on the line (not the delimiter); if the line is empty, returns the line start. */
	private int lineEnd(IDocument doc, int line) {
		try {
			line = clampLine(line);
			IRegion info = doc.getLineInformation(line);
			int len = info.getLength();
			return len == 0 ? info.getOffset() : info.getOffset() + len - 1;
		} catch (BadLocationException e) {
			return 0;
		}
	}

	/** Offset one past the last real character - where Insert-mode 'A' places the caret. */
	private int lineEndExclDelim(IDocument doc, int line) {
		try {
			IRegion info = doc.getLineInformation(clampLine(line));
			return info.getOffset() + info.getLength();
		} catch (BadLocationException e) {
			return 0;
		}
	}

	/** Offset just past the line's delimiter (i.e. the start of the next line, or doc end for the last line). */
	private int lineEndInclDelim(IDocument doc, int line) {
		try {
			line = clampLine(line);
			String delim = doc.getLineDelimiter(line);
			IRegion info = doc.getLineInformation(line);
			int end = info.getOffset() + info.getLength() + (delim == null ? 0 : delim.length());
			return Math.min(end, doc.getLength());
		} catch (BadLocationException e) {
			return doc.getLength();
		}
	}

	private int firstNonBlank(IDocument doc, int line) {
		try {
			IRegion info = doc.getLineInformation(clampLine(line));
			int off = info.getOffset();
			int end = off + info.getLength();
			while (off < end && Character.isWhitespace(doc.getChar(off)) && doc.getChar(off) != '\n' && doc.getChar(off) != '\r') {
				off++;
			}
			return off;
		} catch (BadLocationException e) {
			return lineStart(doc, line);
		}
	}

	private int clampToLine(int offset, int line) {
		IDocument doc = document();
		int from = lineStart(doc, line);
		int to = lineEnd(doc, line);
		return Math.max(from, Math.min(offset, to));
	}

	private int safeOffset(int offset) {
		return Math.max(0, Math.min(offset, document().getLength()));
	}

	private int count() {
		if (countBuffer.length() == 0) {
			return 1;
		}
		try {
			return Math.max(1, Integer.parseInt(countBuffer.toString()));
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	private void setCaret(int offset) {
		viewer.setSelectedRange(safeOffset(offset), 0);
	}

	private void enterInsert(int at) {
		caret = safeOffset(at);
		setCaret(caret);
		mode = Mode.INSERT;
		resetPending();
		updateStatusLine();
	}

	private void doOperation(int op, int times) {
		Object target = editor.getAdapter(ITextOperationTarget.class);
		if (!(target instanceof ITextOperationTarget opTarget)) {
			return;
		}
		for (int i = 0; i < times; i++) {
			if (opTarget.canDoOperation(op)) {
				opTarget.doOperation(op);
			}
		}
		caret = safeOffset(viewer.getSelectedRange().x);
	}

	private static boolean isPureNavigationKey(int keyCode) {
		return keyCode == SWT.ARROW_LEFT || keyCode == SWT.ARROW_RIGHT || keyCode == SWT.ARROW_UP
				|| keyCode == SWT.ARROW_DOWN || keyCode == SWT.HOME || keyCode == SWT.END
				|| keyCode == SWT.PAGE_UP || keyCode == SWT.PAGE_DOWN;
	}

	// ------------------------------------------------------------------
	// System clipboard
	// ------------------------------------------------------------------

	private void copyToClipboard(String text) {
		Clipboard clipboard = VimPlugin.getClipboard();
		clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
	}

	private String getClipboardText() {
		Clipboard clipboard = VimPlugin.getClipboard();
		Object contents = clipboard.getContents(TextTransfer.getInstance());
		return contents instanceof String s ? s : null;
	}

	// ------------------------------------------------------------------
	// Status line: the Eclipse status line is now only used for transient
	// prompts (the "/pattern" search capture, an ex-command error) - the
	// mode itself is shown by the rounded-rectangle ModeBadge instead, per
	// explicit request (a plain-text "-- NORMAL --" string wasn't wanted).
	// ------------------------------------------------------------------

	private IEditorStatusLine statusLine() {
		return editor.getAdapter(IEditorStatusLine.class);
	}

	private void statusMessage(String msg) {
		IEditorStatusLine status = statusLine();
		if (status != null) {
			status.setMessage(false, msg, null);
		}
	}

	private void updateStatusLine() {
		statusMessage("");
		if (modeBadge != null) {
			modeBadge.update(mode);
		}
	}

	// ------------------------------------------------------------------
	// ":" ex-commands, entered through the Spotlight-style CommandPopup.
	// Patterns in :s/// are plain Java regex (no Vim-regex translation) -
	// a known, documented limitation for this first pass.
	// ------------------------------------------------------------------

	private void beginCommand() {
		if (commandPopup != null) {
			commandPopup.open(this::executeExCommand);
		}
	}

	private void executeExCommand(String raw) {
		String cmd = raw.startsWith(":") ? raw.substring(1) : raw;
		cmd = cmd.trim();
		if (cmd.isEmpty()) {
			return;
		}
		try {
			switch (cmd) {
				case "w" -> doSave();
				case "q", "q!" -> closeEditor();
				case "wq", "x" -> { doSave(); closeEditor(); }
				case "noh", "nohlsearch" -> lastSearch = null;
				default -> {
					if (cmd.matches("\\d+")) {
						gotoLine(Integer.parseInt(cmd));
					} else if (cmd.startsWith("%s/") || cmd.startsWith("s/")) {
						runSubstitute(cmd);
					} else {
						statusMessage("E492: Not an editor command: " + cmd);
					}
				}
			}
		} catch (RuntimeException e) {
			VimPlugin.log(e);
		}
	}

	private void doSave() {
		if (editor instanceof ISaveablePart saveable) {
			saveable.doSave(new NullProgressMonitor());
		}
	}

	private void closeEditor() {
		IWorkbenchPage page = editor.getSite().getPage();
		page.closeEditor(editor, false);
	}

	private void gotoLine(int oneBasedLine) {
		IDocument doc = document();
		int line = clampLine(oneBasedLine - 1);
		caret = safeOffset(firstNonBlank(doc, line));
		setCaret(caret);
	}

	/** ":s/pattern/replacement/[g]" (current line) or ":%s/pattern/replacement/[g]" (whole document). */
	private void runSubstitute(String cmd) {
		boolean wholeDoc = cmd.startsWith("%");
		String body = wholeDoc ? cmd.substring(1) : cmd; // now starts with "s/"
		if (!body.startsWith("s/")) {
			return;
		}
		List<String> parts = splitUnescaped(body.substring(2), '/');
		if (parts.size() < 2) {
			return;
		}
		String pattern = parts.get(0);
		String replacement = parts.get(1);
		boolean global = parts.size() > 2 && parts.get(2).contains("g");
		IDocument doc = document();
		try {
			Pattern p = Pattern.compile(pattern);
			int fromLine = wholeDoc ? 0 : lineOf(caret);
			int toLine = wholeDoc ? doc.getNumberOfLines() - 1 : lineOf(caret);
			for (int line = fromLine; line <= toLine; line++) {
				IRegion info = doc.getLineInformation(line);
				String text = doc.get(info.getOffset(), info.getLength());
				Matcher m = p.matcher(text);
				String result = global ? m.replaceAll(replacement) : m.replaceFirst(replacement);
				if (!result.equals(text)) {
					doc.replace(info.getOffset(), info.getLength(), result);
				}
			}
		} catch (java.util.regex.PatternSyntaxException | BadLocationException e) {
			statusMessage("E486: substitute failed: " + e.getMessage());
		}
	}

	/** Splits on an unescaped delimiter, turning "\<delim>" back into a literal <delim>. */
	private static List<String> splitUnescaped(String s, char delim) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == delim) {
				cur.append(delim);
				i++;
			} else if (c == delim) {
				out.add(cur.toString());
				cur.setLength(0);
			} else {
				cur.append(c);
			}
		}
		out.add(cur.toString());
		return out;
	}

	// ------------------------------------------------------------------
	// Caret appearance: thin line in Insert mode and DISABLED mode
	// (StyledText's own default caret, untouched), a solid block covering
	// the whole character cell - with the glyph redrawn in the inverse
	// colour on top, like a terminal - in Normal/Visual mode.
	// ------------------------------------------------------------------

	private void updateCaretAppearance() {
		if (styledText == null || styledText.isDisposed()) {
			return;
		}
		if (mode == Mode.INSERT || mode == Mode.DISABLED) {
			if (styledText.getCaret() != insertCaret) {
				styledText.setCaret(insertCaret);
			}
			return;
		}
		Caret block = buildBlockCaret();
		if (block != null && styledText.getCaret() != block) {
			styledText.setCaret(block);
		}
	}

	private Caret buildBlockCaret() {
		int offset = safeOffset(caret);
		int lineHeight;
		String glyph;
		Font font;
		Color fg;
		Color bg;
		try {
			lineHeight = styledText.getLineHeight(offset);
			int charCount = styledText.getCharCount();
			glyph = " ";
			if (offset < charCount) {
				String s = styledText.getText(offset, offset);
				if (!s.isEmpty() && s.charAt(0) != '\n' && s.charAt(0) != '\r' && s.charAt(0) != '\t') {
					glyph = s;
				}
			}
			StyleRange range = styledText.getStyleRangeAtOffset(offset);
			font = (range != null && range.font != null) ? range.font : styledText.getFont();
			fg = (range != null && range.foreground != null) ? range.foreground : styledText.getForeground();
			bg = (range != null && range.background != null) ? range.background : styledText.getBackground();
		} catch (IllegalArgumentException | SWTException e) {
			// offset momentarily out of sync with the widget (e.g. mid-edit) - skip this refresh
			return blockCaret;
		}

		GC measure = new GC(styledText);
		Point extent;
		try {
			measure.setFont(font);
			extent = measure.textExtent(glyph);
		} finally {
			measure.dispose();
		}
		final int width = Math.max(extent.x, 1);
		final int height = Math.max(lineHeight, extent.y);
		final String drawGlyph = glyph;
		final Font drawFont = font;
		final Color blockColor = fg;
		final Color textColor = bg;

		ImageGcDrawer drawer = (gc, w, h) -> {
			gc.setBackground(blockColor);
			gc.fillRectangle(0, 0, w, h);
			if (!drawGlyph.equals(" ")) {
				gc.setForeground(textColor);
				gc.setFont(drawFont);
				gc.drawString(drawGlyph, 0, 0, false);
			}
		};

		if (blockCaretImage != null && !blockCaretImage.isDisposed()) {
			blockCaretImage.dispose();
		}
		blockCaretImage = new Image(styledText.getDisplay(), drawer, width, height);
		if (blockCaret == null || blockCaret.isDisposed()) {
			blockCaret = new Caret(styledText, SWT.NONE);
		}
		blockCaret.setImage(blockCaretImage);
		blockCaret.setSize(width, height);
		return blockCaret;
	}
}
