package org.eclipse.mieux.mcp.server.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.mieux.mcp.server.json.Json;
import org.eclipse.mieux.mcp.server.protocol.JsonRpcException;
import org.eclipse.mieux.mcp.server.protocol.McpMessageHandler;
import org.eclipse.mieux.mcp.server.registry.ToolRegistry;
import org.junit.jupiter.api.Test;

public class McpMessageHandlerTest {

	private McpMessageHandler newHandler(ToolRegistry registry) {
		return new McpMessageHandler(registry, "eclipse-mieux", "1.0.0");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> parseObject(String json) {
		return (Map<String, Object>) Json.parse(json);
	}

	@Test
	public void initializeReturnsServerInfo() {
		McpMessageHandler handler = newHandler(new ToolRegistry());

		String response = handler
				.handle(Json.write(Json.object("jsonrpc", "2.0", "id", 1L, "method", "initialize", "params",
						Json.object())));

		Map<String, Object> parsed = parseObject(response);
		Map<String, Object> result = (Map<String, Object>) parsed.get("result");
		Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");
		assertEquals("eclipse-mieux", serverInfo.get("name"));
		assertEquals(Long.valueOf(1), parsed.get("id"));
	}

	@Test
	public void toolsListDelegatesToRegistry() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(new FakeTool("echo", args -> "ok"));
		McpMessageHandler handler = newHandler(registry);

		String response = handler.handle(
				Json.write(Json.object("jsonrpc", "2.0", "id", 2L, "method", "tools/list")));

		Map<String, Object> parsed = parseObject(response);
		Map<String, Object> result = (Map<String, Object>) parsed.get("result");
		List<Object> tools = (List<Object>) result.get("tools");
		assertEquals(1, tools.size());
	}

	@Test
	public void toolsCallDispatchesArgumentsToTheTool() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(new FakeTool("echo", args -> args.get("text")));
		McpMessageHandler handler = newHandler(registry);

		String response = handler.handle(Json.write(Json.object("jsonrpc", "2.0", "id", 3L, "method", "tools/call",
				"params", Json.object("name", "echo", "arguments", Json.object("text", "hi there")))));

		Map<String, Object> parsed = parseObject(response);
		Map<String, Object> result = (Map<String, Object>) parsed.get("result");
		List<Object> content = (List<Object>) result.get("content");
		Map<String, Object> block = (Map<String, Object>) content.get(0);
		assertEquals("hi there", block.get("text"));
	}

	@Test
	public void toolsCallWithNonObjectArgumentsReturnsInvalidParamsError() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(new FakeTool("echo", args -> "ok"));
		McpMessageHandler handler = newHandler(registry);

		// "arguments" is an array here, not an object - must be rejected as a
		// client-input problem (INVALID_PARAMS), not surface as a generic
		// INTERNAL_ERROR from Json.asObject() blowing up unhandled.
		String response = handler.handle(Json.write(Json.object("jsonrpc", "2.0", "id", 7L, "method", "tools/call",
				"params", Json.object("name", "echo", "arguments", Json.array("not", "an", "object")))));

		Map<String, Object> parsed = parseObject(response);
		Map<String, Object> error = (Map<String, Object>) parsed.get("error");
		assertEquals(Long.valueOf(JsonRpcException.INVALID_PARAMS), error.get("code"));
	}

	@Test
	public void toolsCallOnUnknownToolReturnsInvalidParamsError() {
		McpMessageHandler handler = newHandler(new ToolRegistry());

		String response = handler.handle(Json.write(Json.object("jsonrpc", "2.0", "id", 4L, "method", "tools/call",
				"params", Json.object("name", "nope"))));

		Map<String, Object> parsed = parseObject(response);
		Map<String, Object> error = (Map<String, Object>) parsed.get("error");
		assertEquals(Long.valueOf(JsonRpcException.INVALID_PARAMS), error.get("code"));
	}

	@Test
	public void unknownMethodReturnsMethodNotFound() {
		McpMessageHandler handler = newHandler(new ToolRegistry());

		String response = handler
				.handle(Json.write(Json.object("jsonrpc", "2.0", "id", 5L, "method", "not/a/real/method")));

		Map<String, Object> parsed = parseObject(response);
		Map<String, Object> error = (Map<String, Object>) parsed.get("error");
		assertEquals(Long.valueOf(JsonRpcException.METHOD_NOT_FOUND), error.get("code"));
	}

	@Test
	public void malformedJsonReturnsParseError() {
		McpMessageHandler handler = newHandler(new ToolRegistry());

		String response = handler.handle("{not json");

		Map<String, Object> parsed = parseObject(response);
		Map<String, Object> error = (Map<String, Object>) parsed.get("error");
		assertEquals(Long.valueOf(JsonRpcException.PARSE_ERROR), error.get("code"));
		assertNull(parsed.get("id"));
	}

	@Test
	public void notificationGetsNoResponse() {
		McpMessageHandler handler = newHandler(new ToolRegistry());

		// No "id" field => JSON-RPC notification, must not produce a response.
		String response = handler.handle(Json.write(Json.object("jsonrpc", "2.0", "method",
				"notifications/initialized")));

		assertNull(response);
	}

	@Test
	public void missingMethodIsInvalidRequest() {
		McpMessageHandler handler = newHandler(new ToolRegistry());

		String response = handler.handle(Json.write(Json.object("jsonrpc", "2.0", "id", 6L)));

		Map<String, Object> parsed = parseObject(response);
		Map<String, Object> error = (Map<String, Object>) parsed.get("error");
		assertEquals(Long.valueOf(JsonRpcException.INVALID_REQUEST), error.get("code"));
		assertTrue(((String) error.get("message")).contains("method"));
	}
}
