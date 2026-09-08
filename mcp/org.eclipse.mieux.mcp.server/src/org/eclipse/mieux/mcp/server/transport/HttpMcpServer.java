package org.eclipse.mieux.mcp.server.transport;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.mieux.mcp.server.protocol.McpMessageHandler;

/**
 * A minimal, loopback-only HTTP+JSON-RPC listener for the MCP server.
 *
 * <p>
 * Single endpoint: {@code POST /mcp}, body is one JSON-RPC request, response
 * is the JSON-RPC response (or {@code 204} for a notification). Every request
 * must carry {@code Authorization: Bearer <token>} matching the token this
 * server was constructed with.
 *
 * <p>
 * Deliberately hand-rolled instead of {@code com.sun.net.httpserver}: that
 * package lives in the JDK's {@code jdk.httpserver} module, which isn't
 * reliably exported into an OSGi bundle's classpath, and pulling in a
 * third-party HTTP server bundle is more dependency than a single endpoint
 * warrants. This only needs to speak enough HTTP/1.1 to be reachable by any
 * HTTP client, not to be a general-purpose server.
 */
public class HttpMcpServer implements Closeable {

	private static final String PATH = "/mcp";

	private final McpMessageHandler handler;
	private final String token;
	private final ExecutorService connectionExecutor;

	private ServerSocket serverSocket;
	private Thread acceptThread;
	private volatile boolean running;

	public HttpMcpServer(McpMessageHandler handler, String token) {
		this.handler = handler;
		this.token = token;
		this.connectionExecutor = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "mcp-server-connection");
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * Starts listening on {@code 127.0.0.1:port} ({@code port == 0} picks an
	 * ephemeral free port).
	 *
	 * @return the port actually bound
	 */
	public synchronized int start(int port) throws IOException {
		if (running) {
			throw new IllegalStateException("Already started");
		}
		serverSocket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
		running = true;
		acceptThread = new Thread(this::acceptLoop, "mcp-server-accept");
		acceptThread.setDaemon(true);
		acceptThread.start();
		return serverSocket.getLocalPort();
	}

	public int getPort() {
		if (serverSocket == null) {
			throw new IllegalStateException("Not started");
		}
		return serverSocket.getLocalPort();
	}

	public boolean isRunning() {
		return running;
	}

	private void acceptLoop() {
		while (running) {
			try {
				Socket socket = serverSocket.accept();
				connectionExecutor.execute(() -> handleConnection(socket));
			} catch (IOException e) {
				// Expected once close() tears down the socket underneath us.
				break;
			}
		}
	}

	private void handleConnection(Socket socket) {
		try (socket) {
			socket.setSoTimeout(10_000);
			InputStream in = socket.getInputStream();
			OutputStream out = socket.getOutputStream();
			HttpRequest request = HttpRequest.read(in);
			respond(out, request);
		} catch (IOException e) {
			// Best-effort: client disconnected, sent garbage, or timed out.
		}
	}

	private void respond(OutputStream out, HttpRequest request) throws IOException {
		if (!"POST".equals(request.method) || !PATH.equals(request.path)) {
			writeResponse(out, 404, "Not Found", "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
			return;
		}
		String authHeader = request.headers.getOrDefault("authorization", "");
		if (!("Bearer " + token).equals(authHeader)) {
			writeResponse(out, 401, "Unauthorized", "text/plain", "unauthorized".getBytes(StandardCharsets.UTF_8));
			return;
		}
		String body = new String(request.body, StandardCharsets.UTF_8);
		String responseJson = handler.handle(body);
		if (responseJson == null) {
			// A JSON-RPC notification carries no response body, but the HTTP
			// exchange still needs to complete.
			writeResponse(out, 204, "No Content", "application/json", new byte[0]);
			return;
		}
		writeResponse(out, 200, "OK", "application/json", responseJson.getBytes(StandardCharsets.UTF_8));
	}

	private void writeResponse(OutputStream out, int status, String statusText, String contentType, byte[] body)
			throws IOException {
		StringBuilder header = new StringBuilder();
		header.append("HTTP/1.1 ").append(status).append(' ').append(statusText).append("\r\n");
		header.append("Content-Type: ").append(contentType).append("; charset=utf-8\r\n");
		header.append("Content-Length: ").append(body.length).append("\r\n");
		header.append("Connection: close\r\n");
		header.append("\r\n");
		out.write(header.toString().getBytes(StandardCharsets.US_ASCII));
		out.write(body);
		out.flush();
	}

	@Override
	public synchronized void close() {
		running = false;
		if (serverSocket != null) {
			try {
				serverSocket.close();
			} catch (IOException e) {
				// ignore - shutting down anyway
			}
		}
		connectionExecutor.shutdownNow();
	}

	private static final class HttpRequest {
		String method = "";
		String path = "";
		final Map<String, String> headers = new HashMap<>();
		byte[] body = new byte[0];

		static HttpRequest read(InputStream in) throws IOException {
			HttpRequest request = new HttpRequest();
			String requestLine = readLine(in);
			if (requestLine == null || requestLine.isEmpty()) {
				throw new IOException("Empty request");
			}
			String[] parts = requestLine.split(" ");
			if (parts.length < 2) {
				throw new IOException("Malformed request line: " + requestLine);
			}
			request.method = parts[0];
			request.path = parts[1];

			String line;
			int contentLength = 0;
			while ((line = readLine(in)) != null && !line.isEmpty()) {
				int colon = line.indexOf(':');
				if (colon < 0) {
					continue;
				}
				String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
				String value = line.substring(colon + 1).trim();
				request.headers.put(name, value);
				if ("content-length".equals(name)) {
					contentLength = Integer.parseInt(value);
				}
			}
			if (contentLength > 0) {
				request.body = in.readNBytes(contentLength);
			}
			return request;
		}

		private static String readLine(InputStream in) throws IOException {
			ByteArrayOutputStream line = new ByteArrayOutputStream();
			int b;
			boolean sawAny = false;
			while ((b = in.read()) != -1) {
				sawAny = true;
				if (b == '\r') {
					continue;
				}
				if (b == '\n') {
					return line.toString(StandardCharsets.ISO_8859_1);
				}
				line.write(b);
			}
			return sawAny ? line.toString(StandardCharsets.ISO_8859_1) : null;
		}
	}
}
