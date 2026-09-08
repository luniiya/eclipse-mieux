package org.eclipse.mieux.vim;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.FindReplaceDocumentAdapter;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.ui.IEditorPart;
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
 * u / Ctrl+R (undo/redo), i I a A o O (enter Insert), v V (Visual/Visual
 * Line) with d/c/y/x/~ acting on the selection, p/P (system-clipboard
 * paste), and / ? n N (search via {@link FindReplaceDocumentAdapter}).
 * Count prefixes (e.g. 3dw, 5j) are supported throughout. Not implemented:
 * named registers (everything is the system clipboard), macros, the "."
 * repeat command, and visual-block mode.
 */
public class VimMode implements VerifyKeyListener {

	private enum Mode {
		NORMAL, INSERT, VISUAL, VISUAL_LINE
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

	public VimMode(IEditorPart editor, ITextViewer viewer) {
		this.editor = editor;
		this.viewer = viewer;
		if (viewer instanceof ITextViewerExtension ext) {
			ext.prependVerifyKeyListener(this);
		}
		this.caret = safeOffset(viewer.getSelectedRange().x);
		updateStatusLine();
	}

	public void dispose() {
		if (viewer instanceof ITextViewerExtension ext) {
			ext.removeVerifyKeyListener(this);
		}
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
	}

	private void dispatch(VerifyEvent event) throws BadLocationException {
		if (searchBuffer != null) {
			handleSearchCapture(event);
			return;
		}
		switch (mode) {
			case INSERT -> handleInsert(event);
			case NORMAL -> handleNormal(event);
			case VISUAL, VISUAL_LINE -> handleVisual(event);
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
		// except the couple of Ctrl-combos Vim itself defines
		int mods = event.stateMask & (SWT.CTRL | SWT.ALT | SWT.COMMAND);
		if (mods == SWT.CTRL && Character.toLowerCase(event.character) == 'r') {
			event.doit = false;
			doOperation(ITextOperationTarget.REDO, count());
			resetPending();
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
		updateSelection();
		updateStatusLine();
	}

	private void handleVisual(VerifyEvent event) throws BadLocationException {
		IDocument doc = document();

		if (event.keyCode == SWT.ESC) {
			event.doit = false;
			caret = Math.min(caret, visualAnchor == caret ? caret : caret);
			setCaret(caret);
			enterNormal();
			return;
		}
		int mods = event.stateMask & (SWT.CTRL | SWT.ALT | SWT.COMMAND);
		if (mods != 0) {
			return;
		}
		char c = event.character;
		if (c == 0) {
			if (isPureNavigationKey(event.keyCode)) {
				return;
			}
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
			case 'v' -> enterNormalFromVisual(false);
			case 'V' -> { mode = Mode.VISUAL_LINE; updateSelection(); updateStatusLine(); }
			case 'd', 'x' -> { deleteSelection(doc); enterNormalFromVisual(true); }
			case 'c', 's' -> { int start = selectionStart(); deleteSelection(doc); enterInsert(start); }
			case 'y' -> { yankSelection(doc); caret = selectionStart(); enterNormalFromVisual(true); }
			case '~' -> { toggleCaseSelection(doc); enterNormalFromVisual(true); }
			default -> { /* ignore */ }
		}
	}

	private void enterNormalFromVisual(boolean caretAlreadySet) {
		if (!caretAlreadySet) {
			setCaret(caret);
		} else {
			setCaret(caret);
		}
		enterNormal();
	}

	private void enterNormal() {
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

	private void updateSelection() {
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
		runSearch(lastSearch, reversed != searchForward ? !searchForward : searchForward);
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
		Clipboard clipboard = VimPlugin.getDefault().getClipboard();
		clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
	}

	private String getClipboardText() {
		Clipboard clipboard = VimPlugin.getDefault().getClipboard();
		Object contents = clipboard.getContents(TextTransfer.getInstance());
		return contents instanceof String s ? s : null;
	}

	// ------------------------------------------------------------------
	// Status line ("-- INSERT --" etc., like real Vim's bottom line)
	// ------------------------------------------------------------------

	private IEditorStatusLine statusLine() {
		return editor.getAdapter(IEditorStatusLine.class);
	}

	private void updateStatusLine() {
		IEditorStatusLine status = statusLine();
		if (status == null) {
			return;
		}
		String msg = switch (mode) {
			case INSERT -> "-- INSERT --";
			case VISUAL -> "-- VISUAL --";
			case VISUAL_LINE -> "-- VISUAL LINE --";
			case NORMAL -> "";
		};
		status.setMessage(false, msg, null);
	}
}
