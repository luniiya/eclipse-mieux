package org.eclipse.mieux.mcp.server.tests;

import java.util.Map;
import java.util.function.Function;

import org.eclipse.mieux.mcp.server.json.Json;
import org.eclipse.mieux.mcp.server.registry.Tool;
import org.eclipse.mieux.mcp.server.registry.ToolExecutionException;

/** A configurable {@link Tool} double for exercising the registry and handler in isolation. */
public class FakeTool implements Tool {

	private final String name;
	private final Function<Map<String, Object>, Object> body;

	public FakeTool(String name, Function<Map<String, Object>, Object> body) {
		this.name = name;
		this.body = body;
	}

	/** A fake tool that always fails with {@code message}, via {@link ToolExecutionException}. */
	public static FakeTool failing(String name, String message) {
		return new FakeTool(name, args -> {
			throw new FakeFailure(message);
		});
	}

	/**
	 * A fake tool that "crashes" with a raw, unchecked exception - simulating a
	 * buggy tool implementation, as opposed to {@link #failing} which reports a
	 * deliberate, well-behaved failure via {@link ToolExecutionException}.
	 */
	public static FakeTool crashing(String name) {
		return new FakeTool(name, args -> {
			throw new IllegalStateException("simulated bug in " + name);
		});
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public String description() {
		return "Fake tool " + name + " for tests";
	}

	@Override
	public Map<String, Object> inputSchema() {
		return Json.object("type", "object");
	}

	@Override
	public Object execute(Map<String, Object> arguments) throws ToolExecutionException {
		try {
			return body.apply(arguments);
		} catch (FakeFailure e) {
			throw new ToolExecutionException(e.getMessage());
		}
	}

	private static final class FakeFailure extends RuntimeException {
		private static final long serialVersionUID = 1L;

		FakeFailure(String message) {
			super(message);
		}
	}
}
