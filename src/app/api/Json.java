package app.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal hand-rolled JSON <em>writer</em> for the local HTTP API. Pure JDK, no
 * third-party library (the dependency policy applies to the engine; this is just
 * not worth a dependency for the handful of flat objects the API emits).
 *
 * <p>Increment 1 is writer-only — every endpoint is GET. A tiny request parser
 * for {@code POST {key,value}} bodies arrives with the {@code /api/dht/*}
 * endpoints in a later increment.
 *
 * <p>Usage: {@code new Json().str("a", "x").num("n", 1).end()} → {@code {"a":"x","n":1}}.
 * Nested arrays/objects are composed by building the inner string and passing it
 * through {@link #raw(String, String)} / {@link #array(List)}.
 */
final class Json {

	private final StringBuilder sb = new StringBuilder("{");
	private boolean first = true;

	private void key(String name) {
		if (!first) {
			sb.append(',');
		}
		first = false;
		sb.append('"').append(escape(name)).append("\":");
	}

	Json str(String name, String value) {
		key(name);
		sb.append(value == null ? "null" : quote(value));
		return this;
	}

	Json num(String name, long value) {
		key(name);
		sb.append(value);
		return this;
	}

	Json bool(String name, boolean value) {
		key(name);
		sb.append(value);
		return this;
	}

	Json intArray(String name, int[] values) {
		key(name);
		sb.append('[');
		for (int i = 0; i < values.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(values[i]);
		}
		sb.append(']');
		return this;
	}

	/** Append {@code name: <rawJson>} verbatim — caller guarantees {@code rawJson} is valid JSON. */
	Json raw(String name, String rawJson) {
		key(name);
		sb.append(rawJson);
		return this;
	}

	/** Close the object and return the JSON string. */
	String end() {
		return sb.append('}').toString();
	}

	/** Build a JSON array from already-encoded element strings (objects, numbers, quoted strings). */
	static String array(List<String> elements) {
		StringBuilder out = new StringBuilder("[");
		for (int i = 0; i < elements.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			out.append(elements.get(i));
		}
		return out.append(']').toString();
	}

	static String quote(String s) {
		return '"' + escape(s) + '"';
	}

	// --------------------------------------------------------------- reader

	/**
	 * Parse a <em>flat</em> JSON object with string keys and string values
	 * ({@code {"key":"v","k2":"v2"}}) into an insertion-ordered map — exactly what
	 * the {@code POST} request bodies need ({@code /api/dht/put} carries
	 * {@code {"key","value"}}). Deliberately minimal: nested objects/arrays,
	 * numbers, and booleans are not accepted (the API has no such request body).
	 * String escapes ({@code \" \\ \/ \n \r \t \b \f \\uXXXX}) are decoded.
	 *
	 * @throws IllegalArgumentException on any malformed input (handlers turn this
	 *         into a 400 response — never a 500)
	 */
	static Map<String, String> parseFlatObject(String json) {
		Map<String, String> out = new LinkedHashMap<>();
		int i = skipWs(json, 0);
		i = expect(json, i, '{');
		i = skipWs(json, i);
		if (i < json.length() && json.charAt(i) == '}') {
			return out; // empty object
		}
		while (true) {
			i = skipWs(json, i);
			StringBuilder key = new StringBuilder();
			i = readString(json, i, key);
			i = skipWs(json, i);
			i = expect(json, i, ':');
			i = skipWs(json, i);
			StringBuilder value = new StringBuilder();
			i = readString(json, i, value);
			out.put(key.toString(), value.toString());
			i = skipWs(json, i);
			if (i >= json.length()) {
				throw new IllegalArgumentException("unterminated object");
			}
			char c = json.charAt(i++);
			if (c == ',') {
				continue;
			}
			if (c == '}') {
				return out;
			}
			throw new IllegalArgumentException("expected ',' or '}' at " + (i - 1));
		}
	}

	private static int skipWs(String s, int i) {
		while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
			i++;
		}
		return i;
	}

	private static int expect(String s, int i, char c) {
		if (i >= s.length() || s.charAt(i) != c) {
			throw new IllegalArgumentException("expected '" + c + "' at " + i);
		}
		return i + 1;
	}

	/** Read a JSON string starting at the opening quote; appends the decoded text, returns index past the close. */
	private static int readString(String s, int i, StringBuilder out) {
		i = expect(s, i, '"');
		while (i < s.length()) {
			char c = s.charAt(i++);
			if (c == '"') {
				return i;
			}
			if (c == '\\') {
				if (i >= s.length()) {
					break;
				}
				char e = s.charAt(i++);
				switch (e) {
					case '"' -> out.append('"');
					case '\\' -> out.append('\\');
					case '/' -> out.append('/');
					case 'n' -> out.append('\n');
					case 'r' -> out.append('\r');
					case 't' -> out.append('\t');
					case 'b' -> out.append('\b');
					case 'f' -> out.append('\f');
					case 'u' -> {
						if (i + 4 > s.length()) {
							throw new IllegalArgumentException("bad \\u escape");
						}
						out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
						i += 4;
					}
					default -> throw new IllegalArgumentException("bad escape \\" + e);
				}
			} else {
				out.append(c);
			}
		}
		throw new IllegalArgumentException("unterminated string");
	}

	static String escape(String s) {
		StringBuilder out = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}
		return out.toString();
	}
}
