package org.eclipse.mieux.mcp.server.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.mieux.mcp.server.protocol.McpMessageHandler;
import org.eclipse.mieux.mcp.server.registry.ToolRegistry;
import org.eclipse.mieux.mcp.server.transport.HttpMcpServer;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/** Plugin lifecycle and endpoint publication for the MCP UI server. */
public final class McpUiPlugin extends AbstractUIPlugin {

	public static final String PLUGIN_ID = "org.eclipse.mieux.mcp.server.ui";
	private static final int DEFAULT_MCP_PORT = 38573;

	private static McpUiPlugin plugin;
	private static HttpMcpServer server;
	private static Path endpointFile;

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		stopServer();
		plugin = null;
		super.stop(context);
	}

	static synchronized void startServer() {
		if (server != null && server.isRunning()) {
			return;
		}
		try {
			ToolRegistry registry = new ToolRegistry();
			UiAutomation automation = new UiAutomation();
			UiTools.registerAll(registry, automation);
			McpMessageHandler handler = new McpMessageHandler(registry, "eclipse-mieux", "1.0");
			// This is a loopback-only endpoint, so keep its address stable for local
			// MCP clients such as Codex and do not require a per-launch token.
			HttpMcpServer newServer = new HttpMcpServer(handler);
			server = newServer;
			int port = newServer.start(mcpPort());
			Path file = endpointPath();
			endpointFile = file;
			writeEndpoint(file, port);
		} catch (Exception e) {
			logError("Could not start the MCP server", e);
			if (server != null) {
				server.close();
				server = null;
			}
		}
	}

	private static int mcpPort() {
		return Integer.getInteger("eclipse.mieux.mcp.port", DEFAULT_MCP_PORT);
	}

	private static synchronized void stopServer() {
		if (server != null) {
			server.close();
			server = null;
		}
		if (endpointFile != null) {
			try {
				Files.deleteIfExists(endpointFile);
			} catch (IOException e) {
				logError("Could not remove the MCP endpoint file", e);
			}
			endpointFile = null;
		}
	}

	private static Path endpointPath() throws IOException {
		String stateHome = System.getenv("XDG_STATE_HOME");
		Path base = stateHome == null || stateHome.isBlank()
				? Path.of(System.getProperty("user.home"), ".local", "state")
				: Path.of(stateHome);
		Path directory = base.resolve("eclipse-mieux");
		Files.createDirectories(directory);
		return directory.resolve("mcp.json");
	}

	private static void writeEndpoint(Path file, int port) throws IOException {
		String json = "{\n" + "  \"host\": \"127.0.0.1\",\n" + "  \"port\": " + port + ",\n"
				+ "  \"endpoint\": \"http://127.0.0.1:" + port + "/mcp\"\n" + "}\n";
		Files.writeString(file, json, StandardCharsets.UTF_8);
		try {
			Set<PosixFilePermission> ownerOnly = EnumSet.of(PosixFilePermission.OWNER_READ,
					PosixFilePermission.OWNER_WRITE);
			Files.setPosixFilePermissions(file, ownerOnly);
		} catch (UnsupportedOperationException e) {
			// The endpoint file is still useful on non-POSIX systems.
		}
	}

	private static void logError(String message, Throwable error) {
		McpUiPlugin current = plugin;
		if (current != null) {
			ILog log = current.getLog();
			log.log(new Status(IStatus.ERROR, PLUGIN_ID, message, error));
		}
	}
}
