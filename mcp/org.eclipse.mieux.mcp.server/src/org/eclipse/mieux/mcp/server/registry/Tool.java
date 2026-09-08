package org.eclipse.mieux.mcp.server.registry;

import java.util.Map;

/**
 * One MCP tool: a named, schema-described action the agent can call.
 *
 * <p>
 * Implementations should be side-effect-free to describe (name/description/
 * schema) and may throw {@link ToolExecutionException} from
 * {@link #execute(Map)} to report a failure that should be surfaced to the
 * caller as a tool error rather than a transport-level fault.
 */
public interface Tool {

	/** Unique tool name, e.g. {@code "ui_snapshot"}. */
	String name();

	/** Human-readable description shown to the agent in {@code tools/list}. */
	String description();

	/**
	 * JSON Schema (as a {@code Map}/{@code List} tree, ready for
	 * {@link org.eclipse.mieux.mcp.server.json.Json#write}) describing the shape
	 * of the {@code arguments} object accepted by {@link #execute(Map)}.
	 */
	Map<String, Object> inputSchema();

	/**
	 * Runs the tool.
	 *
	 * @param arguments parsed JSON-object arguments (never {@code null}; an empty
	 *                  map when the call carried none)
	 * @return a JSON-serializable result value describing what happened
	 * @throws ToolExecutionException if the tool could not complete; carried back
	 *                                to the caller as an {@code isError} tool
	 *                                result rather than a JSON-RPC-level error
	 */
	Object execute(Map<String, Object> arguments) throws ToolExecutionException;
}
