package org.eclipse.mieux.vim;

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.FocusAdapter;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Scrollable;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * A centred, borderless ":" command-entry popup shown over the editor's
 * shell, evocative of a "Spotlight"-style launcher rather than a status-line
 * prompt. ESC (or losing focus) cancels; Enter runs the typed command.
 */
final class CommandPopup {

	private final Scrollable host;
	private final Shell shell;
	private final Text text;
	private Consumer<String> onExecute;

	CommandPopup(Scrollable host) {
		this.host = host;

		shell = new Shell(host.getShell(), SWT.NO_TRIM | SWT.ON_TOP);
		shell.setLayout(new FillLayout(SWT.HORIZONTAL));
		shell.setBackground(host.getDisplay().getSystemColor(SWT.COLOR_WIDGET_DARK_SHADOW));

		text = new Text(shell, SWT.SINGLE | SWT.BORDER);
		text.setFont(host.getFont());

		text.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if (e.keyCode == SWT.ESC) {
					hide();
				} else if (e.character == SWT.CR || e.character == SWT.LF) {
					String raw = text.getText();
					hide();
					if (onExecute != null) {
						onExecute.accept(raw);
					}
				}
			}
		});
		text.addFocusListener(new FocusAdapter() {
			@Override
			public void focusLost(FocusEvent e) {
				hide();
			}
		});
		host.addDisposeListener(e -> dispose());
	}

	/** Shows the popup pre-filled with ":" and focused, calling {@code onExecute} if the user presses Enter. */
	void open(Consumer<String> onExecute) {
		if (shell.isDisposed()) {
			return;
		}
		this.onExecute = onExecute;
		text.setText(":");
		reposition();
		shell.setVisible(true);
		text.setFocus();
		text.setSelection(1, 1);
	}

	private void reposition() {
		Rectangle editorShell = host.getShell().getBounds();
		int w = Math.max(320, editorShell.width / 3);
		int h = text.computeSize(SWT.DEFAULT, SWT.DEFAULT).y + 12;
		int x = editorShell.x + (editorShell.width - w) / 2;
		int y = editorShell.y + editorShell.height / 4;
		shell.setBounds(x, y, w, h);
	}

	private void hide() {
		if (shell.isDisposed()) {
			return;
		}
		shell.setVisible(false);
		if (!host.isDisposed()) {
			host.setFocus();
		}
	}

	void dispose() {
		if (!shell.isDisposed()) {
			shell.dispose();
		}
	}
}
