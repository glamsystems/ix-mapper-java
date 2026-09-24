package systems.glam.ix.proxy;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/// The syntax pass: what RFC 8259 admits and what it does not, and where a refusal says it
/// stopped.
final class JsonSyntaxTests {

  private static String validate(final String text) {
    return JsonSyntax.validate(text.getBytes(StandardCharsets.UTF_8));
  }

  private static final List<String> VALID = List.of(
      "{}", "[]", " {} ", "\t{}\n", "\r{}", "{ }", "[ ]", "\"\"", "1", "-0", "0", "true", "false", "null",
      "{\"a\":1}", "{\"a\" : 1 , \"b\" : [1, 2] }", "[1,[2,[3]],{\"a\":{}}]", "[true,false,null]",
      "\"a\\\"b\"", "\"\\\\ \\/ \\b \\f \\n \\r \\t\"", "\"\\u00e9\\uABCD\\u0000\"", "\"é ✓ 😀\"", "\" \"",
      "\"\\uD83D\\uDE00\"", "\"\\uD800\\uDC00\"", "\"\\uDBFF\\uDFFF\"", "\"\\uD7FF\"", "\"\\uE000\"", "\"\\ud83d\\ude00\"",
      "\"\\uD83D\\uDE00\\\"\"", "\"ab\\uD83D\\uDE00cd\"",
      "10", "-10", "1.5", "-1.5", "0.5e-3", "1e2", "1E2", "1e+2", "1e-2", "-1.25E+10", "1e1000",
      "[" .repeat(64) + "]".repeat(64), "{\"a\":".repeat(64) + "1" + "}".repeat(64)
  );

  @TestFactory
  Stream<DynamicTest> admitsJsonText() {
    return VALID.stream().map(text -> DynamicTest.dynamicTest("<" + (text.length() > 30 ? text.substring(0, 30) + "…" : text) + ">",
        () -> assertNull(validate(text), text)));
  }

  private static final LinkedHashMap<String, String> INVALID = new LinkedHashMap<>();

  static {
    INVALID.put("", "unexpected end of input at offset 0");
    INVALID.put("   ", "unexpected end of input at offset 3");
    INVALID.put("{", "expected a field name at offset 1");
    INVALID.put("{\"a\":", "unexpected end of input at offset 5");
    INVALID.put("{\"a\":1", "unexpected end of input, expected '}' at offset 6");
    INVALID.put("{\"a\":1 x", "expected '}' at offset 7");
    INVALID.put("{\"a\":1:}", "expected '}' at offset 6");
    INVALID.put("{\"a\":1{", "expected '}' at offset 6");
    INVALID.put("{\"a\":1 null", "expected '}' at offset 7");
    INVALID.put("{1:2}", "expected a field name at offset 1");
    INVALID.put("{\"a\":1,}", "expected a field name at offset 7");
    INVALID.put("{\"a\" 1}", "expected ':' at offset 5");
    INVALID.put("[1", "unexpected end of input, expected ']' at offset 2");
    INVALID.put("[1 x", "expected ']' at offset 3");
    INVALID.put("[1 null", "expected ']' at offset 3");
    INVALID.put("[1,]", "unexpected character at offset 3");
    INVALID.put("[,1]", "unexpected character at offset 1");
    INVALID.put("{} x", "content after the document at offset 3");
    INVALID.put("{}{}", "content after the document at offset 2");
    INVALID.put("{} null", "content after the document at offset 3");
    INVALID.put("-01", "content after the document at offset 2");
    INVALID.put("truee", "content after the document at offset 4");
    INVALID.put("\"abc", "unterminated string at offset 4");
    INVALID.put("\"a\\", "unterminated string at offset 3");
    INVALID.put("\"\\x\"", "invalid escape at offset 2");
    INVALID.put("\"\\u12\"", "invalid unicode escape at offset 5");
    INVALID.put("\"\\u12G4\"", "invalid unicode escape at offset 5");
    INVALID.put("\"\\u", "invalid unicode escape at offset 3");
    INVALID.put("\"\\uDE00\"", "unpaired surrogate escape at offset 1");
    INVALID.put("\"\\uDC00\"", "unpaired surrogate escape at offset 1");
    INVALID.put("\"\\uDFFF\"", "unpaired surrogate escape at offset 1");
    INVALID.put("\"\\uD800\"", "unpaired surrogate escape at offset 1");
    INVALID.put("\"\\uDBFF\"", "unpaired surrogate escape at offset 1");
    INVALID.put("\"\\uD83D", "unpaired surrogate escape at offset 1");
    INVALID.put("\"\\uD83Dab\"", "unpaired surrogate escape at offset 1");
    INVALID.put("\"\\uD83D\\n\"", "unpaired surrogate escape at offset 1");
    INVALID.put("\"\\uD83D\\u0041\"", "unpaired surrogate escape at offset 1");
    INVALID.put("\"\\uD83D\\uD83D\"", "unpaired surrogate escape at offset 1");
    INVALID.put("\"\\uD83D\\uDBFF\"", "unpaired surrogate escape at offset 1");
    INVALID.put("\"\\uD83D\\uE000\"", "unpaired surrogate escape at offset 1");
    INVALID.put("\"ab\\uDE00\"", "unpaired surrogate escape at offset 3");
    INVALID.put("\"\\uD83D\\u12\"", "invalid unicode escape at offset 11");
    INVALID.put("\"a\tb\"", "control character in a string at offset 2");
    INVALID.put("\"a\u001fb\"", "control character in a string at offset 2");
    INVALID.put("\"a\nb\"", "control character in a string at offset 2");
    INVALID.put("tru", "invalid literal at offset 0");
    INVALID.put("trux", "invalid literal at offset 0");
    INVALID.put("nul", "invalid literal at offset 0");
    INVALID.put("fals ", "invalid literal at offset 0");
    INVALID.put("[true,fals]", "invalid literal at offset 6");
    INVALID.put("x", "unexpected character at offset 0");
    INVALID.put("\f{}", "unexpected character at offset 0");
    INVALID.put("-", "invalid number at offset 1");
    INVALID.put("-x", "invalid number at offset 1");
    INVALID.put("01", "content after the document at offset 1");
    INVALID.put("1.", "invalid number at offset 2");
    INVALID.put("1.e2", "invalid number at offset 2");
    INVALID.put("1e", "invalid number at offset 2");
    INVALID.put("1e+", "invalid number at offset 3");
    INVALID.put("1e-", "invalid number at offset 3");
    INVALID.put("1.2.3", "content after the document at offset 3");
    INVALID.put("+1", "unexpected character at offset 0");
    INVALID.put(".5", "unexpected character at offset 0");
    INVALID.put("NaN", "unexpected character at offset 0");
    INVALID.put("0x10", "content after the document at offset 1");
    INVALID.put("[".repeat(65) + "]".repeat(65), "nesting deeper than 64 at offset 64");
    INVALID.put("{\"a\":".repeat(65) + "1" + "}".repeat(65), "nesting deeper than 64 at offset 320");
  }

  @TestFactory
  Stream<DynamicTest> refusesWhatIsNotJson() {
    return INVALID.entrySet().stream().map(entry -> DynamicTest.dynamicTest(
        "<" + (entry.getKey().length() > 30 ? entry.getKey().substring(0, 30) + "…" : entry.getKey()) + ">",
        () -> assertEquals(entry.getValue(), validate(entry.getKey()), entry.getKey())));
  }

  private static byte[] bytes(final String hex) {
    return HexFormat.ofDelimiter(" ").parseHex(hex);
  }

  /// A string's bytes, in a quoted string: `22` is the quote.
  private static String quoted(final String hex) {
    return "22 " + hex + " 22";
  }

  /// The UTF-8 sequences RFC 3629 admits, at the bounds of each lead byte.
  private static final List<String> WELL_FORMED = List.of(
      "7F",
      "C2 80", "DF BF",
      "E0 A0 80", "E0 BF BF", "E1 80 80", "EC BF BF", "ED 80 80", "ED 9F BF", "EE 80 80", "EF BF BF",
      "F0 90 80 80", "F0 BF BF BF", "F1 80 80 80", "F3 BF BF BF", "F4 80 80 80", "F4 8F BF BF",
      "C3 A9 F0 9F 98 80 E2 9C 93", "EF BB BF", "EF BF BE"
  );

  @TestFactory
  Stream<DynamicTest> admitsWellFormedUtf8() {
    return WELL_FORMED.stream().map(hex -> DynamicTest.dynamicTest(hex,
        () -> assertNull(JsonSyntax.validate(bytes(quoted(hex))), hex)));
  }

  private static final LinkedHashMap<String, String> MALFORMED = new LinkedHashMap<>();

  static {
    // lone continuation bytes and lead bytes no sequence starts with
    MALFORMED.put("80", "malformed UTF-8 at offset 1");
    MALFORMED.put("BF", "malformed UTF-8 at offset 1");
    MALFORMED.put("C0 80", "malformed UTF-8 at offset 1");
    MALFORMED.put("C1 BF", "malformed UTF-8 at offset 1");
    MALFORMED.put("F5 80 80 80", "malformed UTF-8 at offset 1");
    MALFORMED.put("FE", "malformed UTF-8 at offset 1");
    MALFORMED.put("FF", "malformed UTF-8 at offset 1");
    // a byte that starts no sequence is not read as a lead byte of any length
    MALFORMED.put("80 80 80", "malformed UTF-8 at offset 1");
    MALFORMED.put("C1 BF BF", "malformed UTF-8 at offset 1");
    MALFORMED.put("80 80 80 80", "malformed UTF-8 at offset 1");
    MALFORMED.put("C0 80 80 80", "malformed UTF-8 at offset 1");
    MALFORMED.put("F5 80 80", "malformed UTF-8 at offset 1");
    // a continuation byte out of its range
    MALFORMED.put("C2 7F", "malformed UTF-8 at offset 1");
    MALFORMED.put("C2 C0", "malformed UTF-8 at offset 1");
    MALFORMED.put("E1 7F 80", "malformed UTF-8 at offset 1");
    MALFORMED.put("E1 80 7F", "malformed UTF-8 at offset 1");
    MALFORMED.put("E1 80 C0", "malformed UTF-8 at offset 1");
    MALFORMED.put("F1 80 80 7F", "malformed UTF-8 at offset 1");
    MALFORMED.put("F1 80 80 C0", "malformed UTF-8 at offset 1");
    // overlong forms, encoded surrogates, and beyond U+10FFFF
    MALFORMED.put("E0 9F BF", "malformed UTF-8 at offset 1");
    MALFORMED.put("ED A0 80", "malformed UTF-8 at offset 1");
    MALFORMED.put("ED BF BF", "malformed UTF-8 at offset 1");
    MALFORMED.put("F0 8F BF BF", "malformed UTF-8 at offset 1");
    MALFORMED.put("F4 90 80 80", "malformed UTF-8 at offset 1");
    // truncated by the closing quote or the end of input
    MALFORMED.put("C3 22", "malformed UTF-8 at offset 1");
    MALFORMED.put("E2 82 22", "malformed UTF-8 at offset 1");
    MALFORMED.put("F0 9F 98 22", "malformed UTF-8 at offset 1");
    MALFORMED.put("61 62 C8 75", "malformed UTF-8 at offset 3");
  }

  @TestFactory
  Stream<DynamicTest> refusesMalformedUtf8() {
    return MALFORMED.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(),
        () -> assertEquals(entry.getValue(), JsonSyntax.validate(bytes(quoted(entry.getKey()))), entry.getKey())));
  }

  @Test
  void aTruncatedSequenceAtTheEndOfInputIsMalformed() {
    assertEquals("malformed UTF-8 at offset 1", JsonSyntax.validate(bytes("22 C3")));
    assertEquals("malformed UTF-8 at offset 1", JsonSyntax.validate(bytes("22 E2 82")));
  }

  /// Only the control range is refused as such: DEL passes, NUL does not.
  @Test
  void delIsNotAControlCharacterAndNulIs() {
    assertNull(JsonSyntax.validate(bytes(quoted("7F"))));
    assertEquals("control character in a string at offset 1", JsonSyntax.validate(bytes(quoted("00"))));
  }

  /// Bytes above ASCII are only read inside strings: outside them they are unexpected
  /// characters, not UTF-8.
  @Test
  void utf8OutsideAStringIsAnUnexpectedCharacter() {
    assertEquals("unexpected character at offset 0", JsonSyntax.validate(bytes("C3 A9")));
    assertEquals("unexpected character at offset 0", JsonSyntax.validate(bytes("80")));
  }
}
