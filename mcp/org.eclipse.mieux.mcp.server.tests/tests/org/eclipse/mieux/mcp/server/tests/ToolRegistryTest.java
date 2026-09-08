package org.eclipse.mieux.mcp.server.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.mieux.mcp.server.json.Json;
import org.eclipse.mieux.mcp.server.registry.ToolRegistry;
import org.junit.jupiter.api.Test;

public class ToolRegistryTest {

	@Test
	public void listDescribesRegisteredTools() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(new FakeTool("echo", args -> args.get("text")));

		List<Object> list = registry.list();

		assertEquals(1, list.size());
		@SuppressWarnings("unchecked")
		Map<String, Object> descriptor = (Map<String, Object>) list.get(0);
		assertEquals("echo", descriptor.get("name"));
		assertTrue(descriptor.containsKey("description"));
		assertTrue(descriptor.containsKey("inputSchema"));
	}

	@Test
	public void registeringTheSameNameTwiceFails() {
		ToolRegistry registry = new ToolRegistry();
		registry.register(new FakeTool("dup", args -> "ok"));
		assertThrows(IllegalStateException.class, () -> registry.register(new FakeTool("dup", args -> "ok")));
	}

	@Test
	public void callReturnsToolOutputAsTextContent() throws ToolRegistry.UnknownToolException {
		ToolRegistry registry = new ToolRegistry();
		registry.register(new FakeTool("echo", args -> args.get("text")));

		Map<String, Object> result = registry.call("echo", Json.object("text", "hi"));

		assertEquals(Boolean.FALSE, result.get("isError"));
		@SuppressWarnings("unchecked")
		List<Object> content = (List<Object>) result.get("content");
		@SuppressWarnings("unchecked")
		Map<String, Object> firstBlock = (Map<String, Object>) content.get(0);
		assertEquals("text", firstBlock.get("type"));
		assertEquals("hi", firstBlock.get("text"));
	}

	@Test
	public void callOnUnknownToolThrows() {
		ToolRegistry registry = new ToolRegistry();
		assertThrows(ToolRegistry.UnknownToolException.class, () -> registry.call("nope", Map.of()));
	}

	@Test
	public void toolExecutionFailureBecomesErrorResult() throws ToolRegistry.UnknownToolException {
		ToolRegistry registry = new ToolRegistry();
		registry.register(FakeTool.failing("boom", "kaboom"));

		Map<String, Object> result = registry.call("boom", Map.of());

		assertEquals(Boolean.TRUE, result.get("isError"));
		@SuppressWarnings("unchecked")
		List<Object> content = (List<Object>) result.get("content");
		@SuppressWarnings("unchecked")
		Map<String, Object> firstBlock = (Map<String, Object>) content.get(0);
		assertEquals("kaboom", firstBlock.get("text"));
		assertFalse(content.isEmpty());
	}
}
