package org.eclipse.mieux.vim;

import java.util.EnumMap;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * A small, always-on-top, rounded-rectangle badge pinned to the bottom-right
 * corner of an editor's text widget, showing the current {@link VimMode.Mode}
 * with a mode-specific colour - a replacement for a plain-text status-line
 * indicator. Double-clicking it invokes the given {@link ToggleListener}
 * (used by {@link VimMode} to flip {@link VimMode.Mode#DISABLED} on/off).
 */
final class ModeBadge {

	interface ToggleListener {
		void onDoubleClick();
	}

	private final Control host;
	private final Shell shell;
	private final Canvas canvas;
	private final Map<VimMode.Mode, Color> colors = new EnumMap<>(VimMode.Mode.class);
	private final Color textColor;
	private String label = "NORMAL";
	private Color current;

	ModeBadge(Control host, ToggleListener listener) {
		this.host = host;
		Display display = host.getDisplay();

		shell = new Shell(host.getShell(), SWT.NO_TRIM | SWT.ON_TOP | SWT.NO_FOCUS);
		shell.setLayout(null);
		canvas = new Canvas(shell, SWT.NONE);
		canvas.setBounds(0, 0, 1, 1);

		// blend the badge's square corners into the editor by matching its background;
		// the rounded rect painted on top is what actually reads as "a badge".
		Color editorBg = host.getBackground();
		shell.setBackground(editorBg);
		canvas.setBackground(editorBg);

		colors.put(VimMode.Mode.NORMAL, new Color(display, 70, 130, 220));
		colors.put(VimMode.Mode.INSERT, new Color(display, 60, 170, 90));
		colors.put(VimMode.Mode.VISUAL, new Color(display, 190, 110, 40));
		colors.put(VimMode.Mode.VISUAL_LINE, new Color(display, 190, 110, 40));
		colors.put(VimMode.Mode.VISUAL_BLOCK, new Color(display, 190, 70, 160));
		colors.put(VimMode.Mode.DISABLED, new Color(display, 110, 110, 110));
		textColor = new Color(display, 255, 255, 255);
		current = colors.get(VimMode.Mode.NORMAL);

		canvas.addPaintListener(e -> paint(e.gc));
		canvas.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseDoubleClick(MouseEvent e) {
				listener.onDoubleClick();
			}
		});

		ControlAdapter follow = new ControlAdapter() {
			@Override
			public void controlMoved(ControlEvent e) {
				reposition();
			}

			@Override
			public void controlResized(ControlEvent e) {
				reposition();
			}
		};
		host.addControlListener(follow);
		host.getShell().addControlListener(follow);
		host.addDisposeListener(e -> dispose());

		resize();
		shell.setVisible(true);
	}

	void update(VimMode.Mode mode) {
		if (shell.isDisposed()) {
			return;
		}
		label = switch (mode) {
			case NORMAL -> "NORMAL";
			case INSERT -> "INSERT";
			case VISUAL -> "VISUAL";
			case VISUAL_LINE -> "V-LINE";
			case VISUAL_BLOCK -> "V-BLOCK";
			case DISABLED -> "-- OFF --";
		};
		current = colors.get(mode);
		resize();
		canvas.redraw();
	}

	private void resize() {
		if (canvas.isDisposed()) {
			return;
		}
		GC gc = new GC(canvas);
		Point extent;
		try {
			extent = gc.textExtent(label);
		} finally {
			gc.dispose();
		}
		int w = extent.x + 20;
		int h = extent.y + 10;
		canvas.setBounds(0, 0, w, h);
		shell.setSize(w, h);
		reposition();
	}

	private void reposition() {
		if (host.isDisposed() || shell.isDisposed()) {
			return;
		}
		Rectangle area = host.getClientArea();
		Point bottomRight = host.toDisplay(area.width, area.height);
		Point size = shell.getSize();
		shell.setLocation(bottomRight.x - size.x - 14, bottomRight.y - size.y - 14);
	}

	private void paint(GC gc) {
		Rectangle b = canvas.getBounds();
		gc.setAntialias(SWT.ON);
		gc.setBackground(current);
		gc.fillRoundRectangle(0, 0, b.width - 1, b.height - 1, 12, 12);
		gc.setForeground(textColor);
		Point extent = gc.textExtent(label);
		gc.drawText(label, (b.width - extent.x) / 2, (b.height - extent.y) / 2, true);
	}

	void dispose() {
		if (!shell.isDisposed()) {
			shell.dispose();
		}
		for (Color c : colors.values()) {
			if (!c.isDisposed()) {
				c.dispose();
			}
		}
		if (!textColor.isDisposed()) {
			textColor.dispose();
		}
	}
}
