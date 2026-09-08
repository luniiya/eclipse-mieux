package org.eclipse.mieux.mcp.server.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.eclipse.mieux.mcp.server.json.Json;
import org.eclipse.mieux.mcp.server.protocol.McpMessageHandler;
import org.eclipse.mieux.mcp.server.registry.ToolRegistry;
import org.eclipse.mieux.mcp.server.transport.HttpMcpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link HttpMcpServer} end to end over a real loopback socket,
 * using {@link HttpURLConnection} as an independent client - this is the
 * same shape of request an MCP client will actually make.
 */
public class HttpMcpServerTest {

	private static final String TOKEN = "test-token-123";

	private ToolRegistry registry;
	private HttpMcpServer server;
	private int port;

	@BeforeEach
	public void startServer() throws IOException {
		registry = new ToolRegistry();
		registry.register(new FakeTool("echo", args -> args.get("text")));
		McpMessageHandler handler = new McpMessageHandler(registry, "eclipse-mieux", "1.0.0");
		server = new HttpMcpServer(handler, TOKEN);
		port = server.start(0);
	}

	@AfterEach
	public void stopServer() {
		server.close();
	}

	@Test
	public void bindsOnlyToLoopback() throws IOException {
		// Assert the actual bound address directly, not just "some connection
		// worked" - a wildcard bind (0.0.0.0) would also let loopback connections
		// through, so that alone wouldn't prove anything.
		assertTrue(server.getBoundAddress().isLoopbackAddress());

		HttpURLConnection connection = post("/mcp", TOKEN, "{}");
		assertTrue(connection.getResponseCode() > 0);
	}

	@Test
	public void isRunningReflectsLifecycle() {
		assertTrue(server.isRunning());
		server.close();
		assertFalse(server.isRunning());
	}

	@Test
	public void malformedContentLengthGetsACleanBadRequestResponse() throws IOException {
		// Below the HttpURLConnection level: send a hand-written request with a
		// non-numeric Content-Length, which must not just drop the connection.
		try (java.net.Socket socket = new java.net.Socket(InetAddress.getLoopbackAddress(), port)) {
			String request = "POST /mcp HTTP/1.1\r\n" + "Authorization: Bearer " + TOKEN + "\r\n"
					+ "Content-Length: not-a-number\r\n" + "\r\n";
			socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
			socket.getOutputStream().flush();
			socket.setSoTimeout(5000);
			String statusLine = new java.io.BufferedReader(
					new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
			assertTrue(statusLine != null && statusLine.contains("400"), "expected a 400 status, got: " + statusLine);
		}
	}

	@Test
	public void validRequestWithCorrectTokenSucceeds() throws IOException {
		String body = Json.write(Json.object("jsonrpc", "2.0", "id", 1L, "method", "tools/call", "params",
				Json.object("name", "echo", "arguments", Json.object("text", "hello over http"))));

		HttpURLConnection connection = post("/mcp", TOKEN, body);

		assertEquals(200, connection.getResponseCode());
		String responseBody = readBody(connection);
		@SuppressWarnings("unchecked")
		Map<String, Object> parsed = (Map<String, Object>) Json.parse(responseBody);
		assertTrue(responseBody.contains("hello over http"));
		assertEquals(Long.valueOf(1), parsed.get("id"));
	}

	@Test
	public void missingTokenIsRejected() throws IOException {
		HttpURLConnection connection = post("/mcp", null, "{}");
		assertEquals(401, connection.getResponseCode());
	}

	@Test
	public void wrongTokenIsRejected() throws IOException {
		HttpURLConnection connection = post("/mcp", "totally-wrong-token", "{}");
		assertEquals(401, connection.getResponseCode());
	}

	@Test
	public void unknownPathReturnsNotFound() throws IOException {
		HttpURLConnection connection = post("/not-mcp", TOKEN, "{}");
		assertEquals(404, connection.getResponseCode());
	}

	@Test
	public void notificationGetsNoContentResponse() throws IOException {
		String body = Json.write(Json.object("jsonrpc", "2.0", "method", "notifications/initialized"));
		HttpURLConnection connection = post("/mcp", TOKEN, body);
		assertEquals(204, connection.getResponseCode());
	}

	private HttpURLConnection post(String path, String token, String body) throws IOException {
		URI uri = URI.create("http://127.0.0.1:" + port + path);
		HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
		connection.setRequestMethod("POST");
		connection.setDoOutput(true);
		if (token != null) {
			connection.setRequestProperty("Authorization", "Bearer " + token);
		}
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		connection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
		try (OutputStream out = connection.getOutputStream()) {
			out.write(bytes);
		}
		return connection;
	}

	private String readBody(HttpURLConnection connection) throws IOException {
		try (InputStream in = connection.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
