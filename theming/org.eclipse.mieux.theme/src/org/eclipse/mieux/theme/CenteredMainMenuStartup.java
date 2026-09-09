/*******************************************************************************
 * Copyright (c) 2026 eclipse-mieux contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.mieux.theme;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import org.eclipse.swt.internal.gtk.GTK;
import org.eclipse.swt.internal.gtk.GtkAllocation;

/**
 * Centers the workbench's native GTK main-menu contents.
 * <p>
 * SWT creates the main menu as a native {@code GtkMenuBar}. GTK lays its
 * top-level items out from the leading edge, and the E4 CSS engine has no
 * layout property that can change that. Adding a margin to the first menu
 * item is the smallest native adjustment: the menu remains a real SWT menu,
 * so its actions, mnemonics, keyboard navigation, and contributions continue
 * to work normally.
 */
@SuppressWarnings("restriction")
public class CenteredMainMenuStartup implements IStartup {

	private static final String INSTALLED_LISTENER_KEY = CenteredMainMenuStartup.class.getName();

	@Override
	public void earlyStartup() {
		if (!"gtk".equals(SWT.getPlatform())) {
			return;
		}

		PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
			IWorkbench workbench = PlatformUI.getWorkbench();
			IWindowListener windowListener = new IWindowListener() {
				@Override
				public void windowOpened(IWorkbenchWindow window) {
					install(window);
				}

				@Override
				public void windowClosed(IWorkbenchWindow window) {
					// The SWT listener is owned by the shell and is disposed with it.
				}

				@Override
				public void windowActivated(IWorkbenchWindow window) {
					center(window.getShell());
				}

				@Override
				public void windowDeactivated(IWorkbenchWindow window) {
					// Nothing to do.
				}
			};
			workbench.addWindowListener(windowListener);
			for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
				install(window);
			}
		});
	}

	private static void install(IWorkbenchWindow window) {
		Shell shell = window.getShell();
		if (shell.isDisposed() || shell.getData(INSTALLED_LISTENER_KEY) != null) {
			return;
		}

		shell.setData(INSTALLED_LISTENER_KEY, Boolean.TRUE);
		shell.addListener(SWT.Resize, event -> scheduleCenter(shell));
		shell.addListener(SWT.Show, event -> scheduleCenter(shell));
		scheduleCenter(shell);
	}

	private static void scheduleCenter(Shell shell) {
		if (!shell.isDisposed()) {
			shell.getDisplay().asyncExec(() -> center(shell));
		}
	}

	private static void center(Shell shell) {
		if (shell.isDisposed()) {
			return;
		}

		Menu menuBar = shell.getMenuBar();
		if (menuBar == null || menuBar.isDisposed() || menuBar.getItemCount() == 0) {
			return;
		}
		if (GTK.GTK4) {
			centerGtk4MenuBar(shell, menuBar);
			return;
		}

		MenuItem[] items = menuBar.getItems();
		GtkAllocation menuAllocation = new GtkAllocation();
		GTK.gtk_widget_get_allocation(menuBar.handle, menuAllocation);
		if (menuAllocation.width <= 0) {
			return;
		}

		GtkAllocation firstAllocation = new GtkAllocation();
		GtkAllocation lastAllocation = new GtkAllocation();
		MenuItem first = null;
		MenuItem last = null;
		for (MenuItem item : items) {
			GtkAllocation itemAllocation = new GtkAllocation();
			GTK.gtk_widget_get_allocation(item.handle, itemAllocation);
			if (itemAllocation.width > 0) {
				if (first == null) {
					first = item;
					firstAllocation = itemAllocation;
				}
				last = item;
				lastAllocation = itemAllocation;
			}
		}
		if (first == null || last == null) {
			return;
		}

		int currentStart = menuAllocation.x + firstAllocation.x;
		int currentEnd = menuAllocation.x + lastAllocation.x + lastAllocation.width;
		int menuWidth = currentEnd - currentStart;
		if (menuWidth <= 0) {
			return;
		}

		int targetStart = Math.max(0, (shell.getClientArea().width - menuWidth) / 2);
		int currentMargin = GTK.gtk_widget_get_margin_start(first.handle);
		int targetMargin = Math.max(0, currentMargin + targetStart - currentStart);
		if (targetMargin != currentMargin) {
			GTK.gtk_widget_set_margin_start(first.handle, targetMargin);
		}
	}

	/**
	 * GTK4 stores top-level menu items as {@code GMenuItem} models, not widgets,
	 * so the GTK3 item-allocation approach above cannot be used. The menu-bar
	 * widget itself is still safe to align and, when GTK reports a natural
	 * allocation, can receive the equivalent leading margin.
	 */
	private static void centerGtk4MenuBar(Shell shell, Menu menuBar) {
		GtkAllocation allocation = new GtkAllocation();
		GTK.gtk_widget_get_allocation(menuBar.handle, allocation);
		if (allocation.width <= 0) {
			return;
		}

		int currentMargin = GTK.gtk_widget_get_margin_start(menuBar.handle);
		int naturalWidth = allocation.width - currentMargin;
		if (naturalWidth <= 0 || naturalWidth >= shell.getClientArea().width) {
			return;
		}

		int targetMargin = Math.max(0, (shell.getClientArea().width - naturalWidth) / 2);
		if (targetMargin != currentMargin) {
			GTK.gtk_widget_set_margin_start(menuBar.handle, targetMargin);
		}
	}
}
