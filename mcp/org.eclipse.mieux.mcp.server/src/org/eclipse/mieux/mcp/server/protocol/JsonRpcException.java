package org.eclipse.mieux.mcp.server.protocol;

/**
 * A JSON-RPC 2.0 error, carrying one of the standard error codes (see
 * {@link #PARSE_ERROR} etc.) plus a message. Thrown while handling a request
 * and turned into a JSON-RPC error response by {@link McpMessageHandler}.
 */
public class JsonRpcException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public static final int PARSE_ERROR = -32700;
	public static final int INVALID_REQUEST = -32600;
	public static final int METHOD_NOT_FOUND = -32601;
	public static final int INVALID_PARAMS = -32602;
	public static final int INTERNAL_ERROR = -32603;

	private final int code;

	public JsonRpcException(int code, String message) {
		super(message);
		this.code = code;
	}

	public int getCode() {
		return code;
	}
}
