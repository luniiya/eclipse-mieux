package org.eclipse.mieux.mcp.server.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader/writer for the MCP server.
 *
 * <p>
 * Deliberately hand-rolled rather than pulling in Gson/Jackson: the JSON-RPC
 * subset this server needs is small, and a bundled parser avoids adding an
 * OSGi dependency for it. Values map to plain Java types: {@code null},
 * {@link Boolean}, {@link Long}/{@link Double}, {@link String},
 * {@link List}{@code <Object>} (array) and {@link Map}{@code <String,Object>}
 * (object, insertion-ordered).
 */
public final class Json {

	private Json() {
	}

	/** Thrown when {@link #parse(String)} is given malformed JSON. */
	public static class JsonParseException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public JsonParseException(String message) {
			super(message);
		}
	}

	public static Object parse(String text) {
		Parser parser = new Parser(text);
		parser.skipWhitespace();
		Object value = parser.parseValue();
		parser.skipWhitespace();
		if (!parser.atEnd()) {
			throw new JsonParseException("Unexpected trailing content at index " + parser.pos);
		}
		return value;
	}

	public static String write(Object value) {
		StringBuilder out = new StringBuilder();
		writeValue(value, out);
		return out.toString();
	}

	/** Convenience builder: {@code Json.object("a", 1, "b", "two")}. */
	public static Map<String, Object> object(Object... keyValuePairs) {
		if (keyValuePairs.length % 2 != 0) {
			throw new IllegalArgumentException("object() requires an even number of arguments");
		}
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keyValuePairs.length; i += 2) {
			map.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
		}
		return map;
	}

	public static List<Object> array(Object... items) {
		List<Object> list = new ArrayList<>();
		for (Object item : items) {
			list.add(item);
		}
		return list;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> asObject(Object value) {
		if (!(value instanceof Map)) {
			throw new JsonParseException("Expected a JSON object");
		}
		return (Map<String, Object>) value;
	}

	private static void writeValue(Object value, StringBuilder out) {
		if (value == null) {
			out.append("null");
		} else if (value instanceof String s) {
			writeString(s, out);
		} else if (value instanceof Boolean b) {
			out.append(b.booleanValue());
		} else if (value instanceof Double || value instanceof Float) {
			out.append(value.toString());
		} else if (value instanceof Number n) {
			out.append(n.longValue());
		} else if (value instanceof Map<?, ?> map) {
			out.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (!first) {
					out.append(',');
				}
				first = false;
				writeString(String.valueOf(entry.getKey()), out);
				out.append(':');
				writeValue(entry.getValue(), out);
			}
			out.append('}');
		} else if (value instanceof Iterable<?> iterable) {
			out.append('[');
			boolean first = true;
			for (Object item : iterable) {
				if (!first) {
					out.append(',');
				}
				first = false;
				writeValue(item, out);
			}
			out.append(']');
		} else {
			throw new IllegalArgumentException("Cannot serialize value of type " + value.getClass());
		}
	}

	private static void writeString(String s, StringBuilder out) {
		out.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}
		out.append('"');
	}

	private static final class Parser {
		private final String text;
		private int pos;

		Parser(String text) {
			this.text = text;
		}

		boolean atEnd() {
			return pos >= text.length();
		}

		void skipWhitespace() {
			while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
				pos++;
			}
		}

		char peek() {
			if (atEnd()) {
				throw new JsonParseException("Unexpected end of input");
			}
			return text.charAt(pos);
		}

		void expect(char c) {
			if (atEnd() || text.charAt(pos) != c) {
				throw new JsonParseException("Expected '" + c + "' at index " + pos);
			}
			pos++;
		}

		Object parseValue() {
			skipWhitespace();
			char c = peek();
			return switch (c) {
				case '{' -> parseObject();
				case '[' -> parseArray();
				case '"' -> parseString();
				case 't', 'f' -> parseBoolean();
				case 'n' -> parseNull();
				default -> parseNumber();
			};
		}

		Map<String, Object> parseObject() {
			expect('{');
			Map<String, Object> map = new LinkedHashMap<>();
			skipWhitespace();
			if (!atEnd() && peek() == '}') {
				pos++;
				return map;
			}
			while (true) {
				skipWhitespace();
				String key = parseString();
				skipWhitespace();
				expect(':');
				Object value = parseValue();
				map.put(key, value);
				skipWhitespace();
				char next = peek();
				if (next == ',') {
					pos++;
				} else if (next == '}') {
					pos++;
					break;
				} else {
					throw new JsonParseException("Expected ',' or '}' at index " + pos);
				}
			}
			return map;
		}

		List<Object> parseArray() {
			expect('[');
			List<Object> list = new ArrayList<>();
			skipWhitespace();
			if (!atEnd() && peek() == ']') {
				pos++;
				return list;
			}
			while (true) {
				list.add(parseValue());
				skipWhitespace();
				char next = peek();
				if (next == ',') {
					pos++;
				} else if (next == ']') {
					pos++;
					break;
				} else {
					throw new JsonParseException("Expected ',' or ']' at index " + pos);
				}
			}
			return list;
		}

		String parseString() {
			expect('"');
			StringBuilder sb = new StringBuilder();
			while (true) {
				if (atEnd()) {
					throw new JsonParseException("Unterminated string");
				}
				char c = text.charAt(pos++);
				if (c == '"') {
					break;
				}
				if (c == '\\') {
					if (atEnd()) {
						throw new JsonParseException("Unterminated escape sequence");
					}
					char esc = text.charAt(pos++);
					switch (esc) {
						case '"' -> sb.append('"');
						case '\\' -> sb.append('\\');
						case '/' -> sb.append('/');
						case 'b' -> sb.append('\b');
						case 'f' -> sb.append('\f');
						case 'n' -> sb.append('\n');
						case 'r' -> sb.append('\r');
						case 't' -> sb.append('\t');
						case 'u' -> {
							if (pos + 4 > text.length()) {
								throw new JsonParseException("Truncated unicode escape");
							}
							String hex = text.substring(pos, pos + 4);
							sb.append((char) Integer.parseInt(hex, 16));
							pos += 4;
						}
						default -> throw new JsonParseException("Invalid escape '\\" + esc + "' at index " + pos);
					}
				} else {
					sb.append(c);
				}
			}
			return sb.toString();
		}

		Boolean parseBoolean() {
			if (text.startsWith("true", pos)) {
				pos += 4;
				return Boolean.TRUE;
			}
			if (text.startsWith("false", pos)) {
				pos += 5;
				return Boolean.FALSE;
			}
			throw new JsonParseException("Invalid literal at index " + pos);
		}

		Object parseNull() {
			if (text.startsWith("null", pos)) {
				pos += 4;
				return null;
			}
			throw new JsonParseException("Invalid literal at index " + pos);
		}

		Number parseNumber() {
			int start = pos;
			if (!atEnd() && peek() == '-') {
				pos++;
			}
			while (!atEnd() && Character.isDigit(peek())) {
				pos++;
			}
			boolean isFloating = false;
			if (!atEnd() && peek() == '.') {
				isFloating = true;
				pos++;
				while (!atEnd() && Character.isDigit(peek())) {
					pos++;
				}
			}
			if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
				isFloating = true;
				pos++;
				if (!atEnd() && (peek() == '+' || peek() == '-')) {
					pos++;
				}
				while (!atEnd() && Character.isDigit(peek())) {
					pos++;
				}
			}
			if (pos == start) {
				throw new JsonParseException("Invalid number at index " + pos);
			}
			String token = text.substring(start, pos);
			try {
				// Deliberately not a ternary: `isFloating ? Double.valueOf(token) :
				// Long.valueOf(token)` triggers Java's binary numeric promotion across
				// the two branches (JLS 15.25) and silently turns every Long result
				// into a Double, regardless of which branch actually ran.
				if (isFloating) {
					return Double.valueOf(token);
				}
				return Long.valueOf(token);
			} catch (NumberFormatException e) {
				throw new JsonParseException("Invalid number '" + token + "' at index " + start);
			}
		}
	}
}
