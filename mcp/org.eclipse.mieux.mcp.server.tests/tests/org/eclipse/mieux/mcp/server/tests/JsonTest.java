package org.eclipse.mieux.mcp.server.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.mieux.mcp.server.json.Json;
import org.junit.jupiter.api.Test;

public class JsonTest {

	@Test
	public void parsesPrimitives() {
		assertEquals(null, Json.parse("null"));
		assertEquals(Boolean.TRUE, Json.parse("true"));
		assertEquals(Boolean.FALSE, Json.parse("false"));
		assertEquals(Long.valueOf(42), Json.parse("42"));
		assertEquals(Double.valueOf(3.5), Json.parse("3.5"));
		assertEquals("hello", Json.parse("\"hello\""));
	}

	@Test
	public void parsesEscapesAndUnicode() {
		assertEquals("a\"b\\c\nd", Json.parse("\"a\\\"b\\\\c\\nd\""));
		assertEquals("€", Json.parse("\"\\u20ac\""));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void parsesNestedObjectsAndArrays() {
		Object parsed = Json.parse("{\"a\":1,\"b\":[1,2,{\"c\":true}],\"d\":null}");
		Map<String, Object> map = (Map<String, Object>) parsed;
		assertEquals(Long.valueOf(1), map.get("a"));
		List<Object> array = (List<Object>) map.get("b");
		assertEquals(3, array.size());
		assertEquals(Long.valueOf(1), array.get(0));
		Map<String, Object> nested = (Map<String, Object>) array.get(2);
		assertEquals(Boolean.TRUE, nested.get("c"));
		assertTrue(map.containsKey("d"));
	}

	@Test
	public void roundTripsWrittenObjects() {
		Map<String, Object> value = Json.object("name", "eclipse-mieux", "count", 3L, "items",
				Json.array("a", "b"));
		String written = Json.write(value);
		Object reparsed = Json.parse(written);
		assertEquals(value, reparsed);
	}

	@Test
	public void writeEscapesSpecialCharacters() {
		String written = Json.write("line1\nline2\t\"quoted\"");
		assertEquals("\"line1\\nline2\\t\\\"quoted\\\"\"", written);
	}

	@Test
	public void rejectsMalformedInput() {
		assertThrows(Json.JsonParseException.class, () -> Json.parse("{"));
		assertThrows(Json.JsonParseException.class, () -> Json.parse("{\"a\":}"));
		assertThrows(Json.JsonParseException.class, () -> Json.parse("[1,2"));
		assertThrows(Json.JsonParseException.class, () -> Json.parse("nul"));
		assertThrows(Json.JsonParseException.class, () -> Json.parse("{}x"));
	}
}
