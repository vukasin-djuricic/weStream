package app.api;

import java.util.List;

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
