package org.eclipse.mieux.vim;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * Activator for eclipse-mieux's native Vim mode. Holds the single shared SWT
 * {@link Clipboard}, which is deliberately the same clipboard Ctrl+C/Ctrl+V
 * use ({@code DND.CLIPBOARD}) - yank/delete/paste in Vim mode go through the
 * real system clipboard, not a private "register".
 */
public class VimPlugin extends AbstractUIPlugin {

	public static final String PLUGIN_ID = "org.eclipse.mieux.vim"; //$NON-NLS-1$

	private static VimPlugin instance;
	private static Clipboard clipboard;

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		instance = this;
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		if (clipboard != null && !clipboard.isDisposed()) {
			clipboard.dispose();
		}
		clipboard = null;
		instance = null;
		super.stop(context);
	}

	public static VimPlugin getDefault() {
		return instance;
	}

	/**
	 * The shared system clipboard (DND.CLIPBOARD), lazily created on the
	 * display thread. Deliberately static rather than routed through
	 * {@link #getDefault()}: it only needs a {@link Display}, not a running
	 * activator/{@code BundleContext}, so callers can reach it even before
	 * (or without) {@code start(BundleContext)} having run.
	 */
	public static synchronized Clipboard getClipboard() {
		if (clipboard == null || clipboard.isDisposed()) {
			clipboard = new Clipboard(Display.getDefault());
		}
		return clipboard;
	}

	public static void log(Throwable t) {
		if (instance != null) {
			instance.getLog().log(new Status(IStatus.ERROR, PLUGIN_ID, t.getMessage(), t));
		} else {
			// No running activator (e.g. under test outside OSGi) - don't
			// swallow the error silently.
			t.printStackTrace();
		}
	}
}
