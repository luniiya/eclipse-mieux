package org.eclipse.mieux.mcp.server.transport;

import java.security.SecureRandom;

/**
 * Generates the per-launch bearer token that gates {@link HttpMcpServer}.
 * The server hands out real UI control (clicking buttons, typing into
 * fields), so anything that can reach the loopback port needs to prove it
 * also has local read access to the token - not just "runs on this
 * machine".
 */
public final class ServerToken {

	private static final SecureRandom RANDOM = new SecureRandom();

	private ServerToken() {
	}

	public static String generate() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		StringBuilder hex = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			hex.append(Character.forDigit((b >> 4) & 0xF, 16));
			hex.append(Character.forDigit(b & 0xF, 16));
		}
		return hex.toString();
	}
}
