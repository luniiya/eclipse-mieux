package org.eclipse.mieux.vim;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Entry point wired via the {@code org.eclipse.ui.startup} extension point.
 * This is what makes Vim mode "native": it activates automatically for every
 * workbench window as soon as the IDE starts, with nothing to install.
 */
public class VimStartup implements IStartup {

	@Override
	public void earlyStartup() {
		Display display = PlatformUI.getWorkbench().getDisplay();
		display.asyncExec(() -> {
			if (display.isDisposed()) {
				return;
			}
			VimEditorTracker tracker = VimEditorTracker.getInstance();
			PlatformUI.getWorkbench().addWindowListener(tracker);
			for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
				tracker.hook(window);
			}
		});
	}
}
