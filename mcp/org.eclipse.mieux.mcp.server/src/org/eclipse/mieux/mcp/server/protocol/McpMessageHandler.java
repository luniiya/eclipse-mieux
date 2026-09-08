package org.eclipse.mieux.mcp.server.protocol;

import java.util.Map;

import org.eclipse.mieux.mcp.server.json.Json;
import org.eclipse.mieux.mcp.server.registry.ToolRegistry;

/**
 * Turns one raw JSON-RPC request string into one raw JSON-RPC response
 * string, per the MCP {@code initialize} / {@code tools/list} /
 * {@code tools/call} methods. No I/O of its own - {@link #handle(String)} is
 * a pure function, which is what makes it testable without a socket.
 */
public class McpMessageHandler {

	private static final String PROTOCOL_VERSION = "2025-06-18";

	private final ToolRegistry registry;
	private final String serverName;
	private final String serverVersion;

	public McpMessageHandler(ToolRegistry registry, String serverName, String serverVersion) {
		this.registry = registry;
		this.serverName = serverName;
		this.serverVersion = serverVersion;
	}

	/**
	 * @return the JSON-RPC response body, or {@code null} if {@code rawRequest}
	 *         was a notification (no {@code id}), which per JSON-RPC 2.0 gets no
	 *         response at all
	 */
	public String handle(String rawRequest) {
		Object id = null;
		boolean isNotification = false;
		try {
			if (rawRequest == null) {
				return Json.write(errorResponse(null, JsonRpcException.INVALID_REQUEST, "Request body is empty"));
			}
			Object parsed;
			try {
				parsed = Json.parse(rawRequest);
			} catch (Json.JsonParseException e) {
				return Json.write(errorResponse(null, JsonRpcException.PARSE_ERROR, "Parse error: " + e.getMessage()));
			}
			if (!(parsed instanceof Map)) {
				return Json.write(
						errorResponse(null, JsonRpcException.INVALID_REQUEST, "Request must be a JSON object"));
			}
			Map<String, Object> request = Json.asObject(parsed);
			isNotification = !request.containsKey("id");
			id = request.get("id");
			Object methodValue = request.get("method");
			if (!(methodValue instanceof String method)) {
				throw new JsonRpcException(JsonRpcException.INVALID_REQUEST, "Missing \"method\"");
			}
			Object result = dispatch(method, request.get("params"));
			if (isNotification) {
				return null;
			}
			return Json.write(successResponse(id, result));
		} catch (JsonRpcException e) {
			if (isNotification) {
				// Notifications never get a response, even a failed one - JSON-RPC 2.0.
				return null;
			}
			return Json.write(errorResponse(id, e.getCode(), e.getMessage()));
		} catch (RuntimeException e) {
			if (isNotification) {
				return null;
			}
			return Json.write(errorResponse(id, JsonRpcException.INTERNAL_ERROR, "Internal error: " + e.getMessage()));
		}
	}

	private Object dispatch(String method, Object paramsValue) {
		return switch (method) {
			case "initialize" -> handleInitialize();
			case "notifications/initialized" -> Json.object();
			case "tools/list" -> Json.object("tools", registry.list());
			case "tools/call" -> handleToolsCall(paramsValue);
			default -> throw new JsonRpcException(JsonRpcException.METHOD_NOT_FOUND, "Method not found: " + method);
		};
	}

	private Object handleInitialize() {
		return Json.object("protocolVersion", PROTOCOL_VERSION, "serverInfo",
				Json.object("name", serverName, "version", serverVersion), "capabilities",
				Json.object("tools", Json.object()));
	}

	private Object handleToolsCall(Object paramsValue) {
		if (!(paramsValue instanceof Map)) {
			throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "\"params\" must be an object");
		}
		Map<String, Object> params = Json.asObject(paramsValue);
		Object nameValue = params.get("name");
		if (!(nameValue instanceof String name)) {
			throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "\"params.name\" is required");
		}
		Object argumentsValue = params.get("arguments");
		if (argumentsValue != null && !(argumentsValue instanceof Map)) {
			throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "\"params.arguments\" must be an object");
		}
		Map<String, Object> arguments = argumentsValue == null ? Map.of() : Json.asObject(argumentsValue);
		try {
			return registry.call(name, arguments);
		} catch (ToolRegistry.UnknownToolException e) {
			throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, e.getMessage());
		}
	}

	private static Map<String, Object> successResponse(Object id, Object result) {
		return Json.object("jsonrpc", "2.0", "id", id, "result", result);
	}

	private static Map<String, Object> errorResponse(Object id, int code, String message) {
		return Json.object("jsonrpc", "2.0", "id", id, "error", Json.object("code", (long) code, "message", message));
	}
}
