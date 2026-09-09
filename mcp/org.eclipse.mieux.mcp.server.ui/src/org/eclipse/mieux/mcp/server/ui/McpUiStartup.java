package org.eclipse.mieux.mcp.server.ui;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;

/** Starts the loopback MCP endpoint once the workbench has a display. */
public final class McpUiStartup implements IStartup {

	@Override
	public void earlyStartup() {
		Display display = Display.getDefault();
		if (display != null && !display.isDisposed()) {
			display.asyncExec(McpUiPlugin::startServer);
		}
	}
}
