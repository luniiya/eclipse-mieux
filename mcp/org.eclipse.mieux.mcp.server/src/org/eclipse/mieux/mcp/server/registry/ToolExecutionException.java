package org.eclipse.mieux.mcp.server.registry;

/**
 * Raised by a {@link Tool} when it cannot complete its requested action.
 * Distinct from a JSON-RPC transport error: this becomes a tool result with
 * {@code isError: true}, not a protocol-level failure, so the agent sees it
 * as "the action failed" rather than "the request was malformed".
 */
public class ToolExecutionException extends Exception {
	private static final long serialVersionUID = 1L;

	public ToolExecutionException(String message) {
		super(message);
	}

	public ToolExecutionException(String message, Throwable cause) {
		super(message, cause);
	}
}
