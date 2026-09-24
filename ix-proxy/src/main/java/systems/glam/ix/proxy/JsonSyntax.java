package systems.glam.ix.proxy;

/// RFC 8259 over a byte array: one JSON value in well-formed UTF-8, then whitespace, and
/// nothing else. The streaming reader behind [MappingDocumentParser] is lenient about
/// separators, terminators, number spellings and control characters, and refuses only some
/// malformed UTF-8 and surrogate escapes, with its own exception; so a document passes
/// through this check first, which refuses everything the grammar and RFC 3629 refuse, plus
/// an unpaired surrogate escape and nesting deeper than [#MAX_DEPTH], more than the reader
/// needs, and the reader only ever sees text it reads whole.
///
/// The cursor moves through [#take()] alone; nothing else assigns it, so no arithmetic can
/// turn a scan around, and a reversed cursor fails on its first read.
final class JsonSyntax {

  /// Deeper nesting than this is refused; a document nests five levels.
  static final int MAX_DEPTH = 64;

  private final byte[] json;
  private int i;

  private JsonSyntax(final byte[] json) {
    this.json = json;
  }

  /// Null when the bytes are one JSON value with nothing but whitespace after it; otherwise
  /// what is wrong and where, as `<what> at offset <n>`.
  static String validate(final byte[] json) {
    final var syntax = new JsonSyntax(json);
    try {
      syntax.whitespace();
      syntax.value(0);
      syntax.whitespace();
      if (syntax.more()) {
        throw syntax.fail("content after the document");
      }
      return null;
    } catch (final Invalid invalid) {
      return invalid.getMessage();
    }
  }

  private static final class Invalid extends RuntimeException {

    private Invalid(final String message) {
      super(message, null, false, false);
    }
  }

  private Invalid failAt(final int offset, final String what) {
    return new Invalid(what + " at offset " + offset);
  }

  private Invalid fail(final String what) {
    return failAt(i, what);
  }

  private boolean more() {
    return i < json.length;
  }

  /// The byte at the cursor, unsigned; callers check [#more()] first.
  private int peek() {
    return json[i] & 0xff;
  }

  /// The byte at the cursor, unsigned, and the cursor moves past it.
  private int take() {
    return json[i++] & 0xff;
  }

  private boolean at(final char c) {
    return more() && peek() == c;
  }

  private void expect(final char c) {
    if (!at(c)) {
      throw fail(more() ? "expected '" + c + "'" : "unexpected end of input, expected '" + c + "'");
    }
    take();
  }

  private void whitespace() {
    while (more()) {
      final int b = peek();
      if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
        take();
      } else {
        return;
      }
    }
  }

  private void value(final int depth) {
    if (!more()) {
      throw fail("unexpected end of input");
    }
    switch (peek()) {
      case '{' -> object(depth + 1);
      case '[' -> array(depth + 1);
      case '"' -> string();
      case 't' -> literal("true");
      case 'f' -> literal("false");
      case 'n' -> literal("null");
      default -> number();
    }
  }

  private void object(final int depth) {
    if (depth > MAX_DEPTH) {
      throw fail("nesting deeper than " + MAX_DEPTH);
    }
    expect('{');
    whitespace();
    if (at('}')) {
      take();
      return;
    }
    while (true) {
      if (!at('"')) {
        throw fail("expected a field name");
      }
      string();
      whitespace();
      expect(':');
      whitespace();
      value(depth);
      whitespace();
      if (at(',')) {
        take();
        whitespace();
        continue;
      }
      expect('}');
      return;
    }
  }

  private void array(final int depth) {
    if (depth > MAX_DEPTH) {
      throw fail("nesting deeper than " + MAX_DEPTH);
    }
    expect('[');
    whitespace();
    if (at(']')) {
      take();
      return;
    }
    while (true) {
      value(depth);
      whitespace();
      if (at(',')) {
        take();
        whitespace();
        continue;
      }
      expect(']');
      return;
    }
  }

  /// A string: escapes by the grammar, surrogate escapes in pairs, and the bytes between them
  /// well-formed UTF-8 with nothing below U+0020.
  private void string() {
    expect('"');
    while (true) {
      if (!more()) {
        throw fail("unterminated string");
      }
      final int b = take();
      if (b == '"') {
        return;
      }
      if (b < 0x20) {
        throw failAt(i - 1, "control character in a string");
      }
      if (b == '\\') {
        escape();
      } else if (b >= 0x80) {
        sequence(b);
      }
    }
  }

  /// The escape whose backslash was just taken; a high surrogate escape takes its low
  /// surrogate escape with it.
  private void escape() {
    final int backslash = i - 1;
    if (!more()) {
      throw fail("unterminated string");
    }
    final int e = take();
    switch (e) {
      case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {
      }
      case 'u' -> {
        final int unit = hex4();
        if (unit >= 0xDC00 && unit <= 0xDFFF) {
          throw failAt(backslash, "unpaired surrogate escape");
        }
        if (unit >= 0xD800 && unit <= 0xDBFF) {
          if (!at('\\')) {
            throw failAt(backslash, "unpaired surrogate escape");
          }
          take();
          if (!at('u')) {
            throw failAt(backslash, "unpaired surrogate escape");
          }
          take();
          final int low = hex4();
          if (low < 0xDC00 || low > 0xDFFF) {
            throw failAt(backslash, "unpaired surrogate escape");
          }
        }
      }
      default -> throw failAt(i - 1, "invalid escape");
    }
  }

  /// Four hex digits at the cursor, as a code unit.
  private int hex4() {
    int unit = 0;
    for (int k = 0; k < 4; k++) {
      final int digit = more() ? Character.digit(peek(), 16) : -1;
      if (digit < 0) {
        throw fail("invalid unicode escape");
      }
      take();
      unit = (unit << 4) | digit;
    }
    return unit;
  }

  /// The rest of the UTF-8 sequence whose lead byte was just taken: two to four bytes in all,
  /// no overlong form, no encoded surrogate, nothing above U+10FFFF (RFC 3629).
  private void sequence(final int lead) {
    final int start = i - 1;
    final int length;
    int min = 0x80;
    int max = 0xBF;
    if (lead >= 0xC2 && lead <= 0xDF) {
      length = 2;
    } else if (lead >= 0xE0 && lead <= 0xEF) {
      length = 3;
      if (lead == 0xE0) {
        min = 0xA0;
      } else if (lead == 0xED) {
        max = 0x9F;
      }
    } else if (lead >= 0xF0 && lead <= 0xF4) {
      length = 4;
      if (lead == 0xF0) {
        min = 0x90;
      } else if (lead == 0xF4) {
        max = 0x8F;
      }
    } else {
      throw failAt(start, "malformed UTF-8");
    }
    for (int k = 1; k < length; k++) {
      if (!more()) {
        throw failAt(start, "malformed UTF-8");
      }
      final int continuation = peek();
      // the first continuation byte carries the lead byte's bounds; the rest the plain ones
      if (continuation < (k == 1 ? min : 0x80) || continuation > (k == 1 ? max : 0xBF)) {
        throw failAt(start, "malformed UTF-8");
      }
      take();
    }
  }

  private void literal(final String text) {
    final int start = i;
    for (int k = 0; k < text.length(); k++) {
      if (!more() || take() != text.charAt(k)) {
        throw failAt(start, "invalid literal");
      }
    }
  }

  /// `-? (0 | [1-9][0-9]*) (. [0-9]+)? ([eE] [+-]? [0-9]+)?`
  private void number() {
    final int start = i;
    if (at('-')) {
      take();
    }
    if (at('0')) {
      take();
    } else if (!digits()) {
      throw fail(i == start ? "unexpected character" : "invalid number");
    }
    if (at('.')) {
      take();
      if (!digits()) {
        throw fail("invalid number");
      }
    }
    if (at('e') || at('E')) {
      take();
      if (at('+') || at('-')) {
        take();
      }
      if (!digits()) {
        throw fail("invalid number");
      }
    }
  }

  /// Whether the cursor passed at least one digit.
  private boolean digits() {
    final int start = i;
    while (more() && peek() >= '0' && peek() <= '9') {
      take();
    }
    return i > start;
  }
}
