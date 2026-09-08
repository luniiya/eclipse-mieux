package org.eclipse.mieux.mcp.server.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.mieux.mcp.server.json.Json;

/**
 * Holds the set of {@link Tool}s this server exposes and dispatches
 * {@code tools/call} requests to them.
 */
public class ToolRegistry {

	private final Map<String, Tool> tools = new LinkedHashMap<>();

	public void register(Tool tool) {
		if (tools.containsKey(tool.name())) {
			throw new IllegalStateException("Tool already registered: " + tool.name());
		}
		tools.put(tool.name(), tool);
	}

	public boolean contains(String name) {
		return tools.containsKey(name);
	}

	/** {@code tools/list} payload: one descriptor per registered tool. */
	public List<Object> list() {
		List<Object> descriptors = new ArrayList<>();
		for (Tool tool : tools.values()) {
			descriptors.add(Json.object("name", tool.name(), "description", tool.description(), "inputSchema",
					tool.inputSchema()));
		}
		return descriptors;
	}

	/**
	 * Runs {@code name} with {@code arguments} and returns an MCP tool-call
	 * result: {@code {"content": [{"type": "text", "text": ...}], "isError": bool}}.
	 *
	 * @throws UnknownToolException if no tool is registered under {@code name}
	 */
	public Map<String, Object> call(String name, Map<String, Object> arguments) throws UnknownToolException {
		Tool tool = tools.get(name);
		if (tool == null) {
			throw new UnknownToolException(name);
		}
		Map<String, Object> args = arguments != null ? arguments : Map.of();
		try {
			Object result = tool.execute(args);
			String text = (result instanceof String s) ? s : Json.write(result);
			return Json.object("content", Json.array(Json.object("type", "text", "text", text)), "isError",
					Boolean.FALSE);
		} catch (ToolExecutionException e) {
			return Json.object("content", Json.array(Json.object("type", "text", "text", e.getMessage())), "isError",
					Boolean.TRUE);
		}
	}

	/** Thrown by {@link #call(String, Map)} when the tool name is not registered. */
	public static class UnknownToolException extends Exception {
		private static final long serialVersionUID = 1L;

		public UnknownToolException(String name) {
			super("Unknown tool: " + name);
		}
	}
}
