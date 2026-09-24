package systems.glam.ix.proxy;

import systems.comodal.jsoniter.JsonIterator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// A JSON tree for the tests: read any JSON into maps, lists, strings, longs, doubles,
/// booleans and nulls, and write such a tree back out, so a case or a document can be
/// patched in place the way the TypeScript tests patch theirs.
final class Json {

  private Json() {
  }

  static Object read(final String json) {
    return read(JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8)));
  }

  static Object read(final byte[] json) {
    return read(JsonIterator.parse(json));
  }

  static Object read(final JsonIterator ji) {
    return switch (ji.whatIsNext()) {
      case OBJECT -> {
        final var map = new LinkedHashMap<String, Object>();
        ji.testObject((buf, offset, len, iterator) -> {
          map.put(new String(buf, offset, len), read(iterator));
          return true;
        });
        yield map;
      }
      case ARRAY -> {
        final var list = new ArrayList<>();
        while (ji.readArray()) {
          list.add(read(ji));
        }
        yield list;
      }
      case STRING -> ji.readString();
      case NUMBER -> {
        final var text = ji.readNumberAsString();
        yield text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0
            ? (Object) Double.parseDouble(text)
            : (Object) Long.parseLong(text);
      }
      case BOOLEAN -> ji.readBoolean();
      case NULL -> {
        ji.readNull();
        yield null;
      }
      case INVALID -> throw new IllegalStateException("invalid JSON");
    };
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> object(final Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  static List<Object> array(final Object value) {
    return (List<Object>) value;
  }

  static String write(final Object value) {
    final var sb = new StringBuilder();
    write(sb, value);
    return sb.toString();
  }

  private static void write(final StringBuilder sb, final Object value) {
    switch (value) {
      case null -> sb.append("null");
      case String s -> {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
          final char c = s.charAt(i);
          switch (c) {
            case '"' -> sb.append("\\\"");
            case '\\' -> sb.append("\\\\");
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            default -> {
              if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
              } else {
                sb.append(c);
              }
            }
          }
        }
        sb.append('"');
      }
      case Boolean b -> sb.append(b);
      case Number n -> sb.append(n);
      case Map<?, ?> map -> {
        sb.append('{');
        boolean first = true;
        for (final var entry : map.entrySet()) {
          if (!first) {
            sb.append(',');
          }
          first = false;
          write(sb, entry.getKey().toString());
          sb.append(':');
          write(sb, entry.getValue());
        }
        sb.append('}');
      }
      case List<?> list -> {
        sb.append('[');
        boolean first = true;
        for (final var element : list) {
          if (!first) {
            sb.append(',');
          }
          first = false;
          write(sb, element);
        }
        sb.append(']');
      }
      default -> throw new IllegalArgumentException("not a JSON value: " + value.getClass());
    }
  }
}
