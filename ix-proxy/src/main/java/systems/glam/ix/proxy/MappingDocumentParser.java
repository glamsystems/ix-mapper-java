package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;
import software.sava.core.programs.Discriminator;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import java.util.Set;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// Admits JSON as a [MappingDocument], refusing what a mapper could not act on safely: an
/// unknown field or label anywhere, a schema version this mapper does not know, seats that are
/// not dense from 0, a source position forwarded twice or forwarded read-only while the native
/// position is writable, an omittable optional ahead of a required position, a seat after an
/// omittable one or omittable seats out of source order, a sentinel seat that does not forward
/// an optional the client passes as the program id, an unknown dynamic account, and a
/// discriminator that is a prefix of another's, since matching is by prefix.
///
/// A refusal carries the document contract's message where the contract has one, and this
/// parser's own where it refuses more (the tracking issue lists those). An address must
/// decode to 32 bytes.
public final class MappingDocumentParser {

  private MappingDocumentParser() {
  }

  /// Parses one document from its bytes. The whole input is one JSON text (RFC 8259, UTF-8,
  /// surrogate escapes paired, nesting at most 64 deep, nothing after the document);
  /// anything else is refused as `is not JSON`, and a document that does not admit is
  /// refused with the field's message, both under the label.
  ///
  /// @param label which document the messages of a refusal are about
  public static MappingDocument parse(final byte[] json, final String label) {
    final var syntax = JsonSyntax.validate(json);
    if (syntax != null) {
      throw new MappingDocumentException(label, "is not JSON: " + syntax);
    }
    return parse(JsonIterator.parse(json), label);
  }

  /// Parses one document from a string, as its UTF-8 bytes: an unpaired surrogate in the
  /// string is replaced on the way, and a refusal's offset counts bytes.
  public static MappingDocument parse(final String json, final String label) {
    return parse(json.getBytes(StandardCharsets.UTF_8), label);
  }

  /// Over JSON text that [JsonSyntax] admitted: the reader is lenient on its own.
  static MappingDocument parse(final JsonIterator ji, final String label) {
    if (ji.whatIsNext() != ValueType.OBJECT) {
      throw new MappingDocumentException(label, "must be an object");
    }
    final var document = new DocumentBuilder(label);
    ji.testObject(document);
    return document.build();
  }

  private static PublicKey decodeAddress(final String value) {
    return decodeAddress(value, PublicKey::fromBase58Encoded);
  }

  /// A 32-byte key spells as 32 to 44 characters of the base58 alphabet; a string outside
  /// that bound never reaches the decoder, whose cost grows with the square of the length,
  /// and one inside it must decode to 32 bytes.
  static PublicKey decodeAddress(final String value, final Function<String, PublicKey> decoder) {
    if (value.length() < 32 || value.length() > 44) {
      return null;
    }
    for (int i = 0; i < value.length(); i++) {
      if (BASE58_ALPHABET.indexOf(value.charAt(i)) < 0) {
        return null;
      }
    }
    try {
      return decoder.apply(value);
    } catch (final RuntimeException e) {
      return null;
    }
  }

  private static final String BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

  private static MappingDocumentException refuse(final String at, final String message) {
    return new MappingDocumentException(at, message);
  }

  // Field readers. Each reads the value at the iterator's position, refusing a value of the
  // wrong type with the contract's words.

  private static String nonBlankString(final JsonIterator ji, final String at, final String field) {
    if (ji.whatIsNext() != ValueType.STRING) {
      ji.skip();
      throw refuse(at, field + " must be a non-blank string");
    }
    final var value = ji.readString();
    if (isBlank(value)) {
      throw refuse(at, field + " must be a non-blank string");
    }
    return value;
  }

  /// Blank as `trim()` leaves it empty: the WhiteSpace and LineTerminator characters of
  /// ECMAScript, which include the no-break and byte-order-mark spaces and every space
  /// separator, and exclude the other control characters.
  static boolean isBlank(final String value) {
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      // the space separators (space and no-break space among them) are one category; the
      // rest are named one by one
      final boolean whitespace = c == '\t' || c == 0x0B || c == '\f' || c == 0xFEFF
          || c == '\n' || c == '\r' || c == 0x2028 || c == 0x2029
          || Character.getType(c) == Character.SPACE_SEPARATOR;
      if (!whitespace) {
        return false;
      }
    }
    return true;
  }

  private static PublicKey address(final JsonIterator ji, final String at, final String field) {
    final var text = nonBlankString(ji, at, field);
    final var address = decodeAddress(text);
    if (address == null) {
      throw refuse(at, field + " is not an address");
    }
    return address;
  }

  private static boolean bool(final JsonIterator ji, final String at, final String field) {
    if (ji.whatIsNext() != ValueType.BOOLEAN) {
      ji.skip();
      throw refuse(at, field + " must be a boolean");
    }
    return ji.readBoolean();
  }

  /// The integer a JSON number denotes to JavaScript, which reads every number as a binary64
  /// value: `1.0`, `1e2` and `1.0000000000000001` are the integers 1, 100 and 1; `1.5` and
  /// `1e1000` (Infinity) are not. Null for a value that is not an integer in int range; a
  /// seat, position, byte or version past that range names nothing, so it is refused with the
  /// rest. The token is a JSON number, which [JsonSyntax] admitted ahead of the reader.
  static Integer integerValue(final String text) {
    final double value = Double.parseDouble(text);
    if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      return null;
    }
    return (int) value;
  }

  /// A JSON number as `String(number)` prints it in JavaScript: `1.0` is `1`, `1e2` is `100`,
  /// `1e21` is `1e+21`, `1e-7` is `1e-7`, `1e1000` is `Infinity`. The digits are the ones
  /// `Double.toString` chooses.
  static String plainNumber(final String text) {
    final double value = Double.parseDouble(text);
    if (Double.isInfinite(value)) {
      return Double.toString(value);
    }
    if (value < 0) {
      return "-" + plainNumber(Double.toString(-value));
    }
    // the digits Double.toString chooses, then the ECMAScript layout: exponent form beyond
    // 1e21 and below 1e-6, plain digits, a point inside the digits, or a leading "0."
    final var decimal = new java.math.BigDecimal(Double.toString(value)).stripTrailingZeros();
    final var digits = decimal.unscaledValue().toString();
    final int k = digits.length();
    final int n = k - decimal.scale();
    if (n > 21 || n <= -6) {
      final int e = n - 1;
      final var exponent = "e" + (e > 0 ? "+" : "") + e;
      return k == 1 ? digits + exponent : digits.charAt(0) + "." + digits.substring(1) + exponent;
    }
    if (k <= n) {
      return digits + "0".repeat(n - k);
    }
    if (n > 0) {
      return digits.substring(0, n) + "." + digits.substring(n);
    }
    return "0." + "0".repeat(-n) + digits;
  }

  /// A JSON number that is a whole, non-negative value small enough for an int.
  private static int nonNegativeInteger(final JsonIterator ji, final String at, final String field) {
    if (ji.whatIsNext() != ValueType.NUMBER) {
      ji.skip();
      throw refuse(at, field + " must be a non-negative integer");
    }
    final var value = integerValue(ji.readNumberAsString());
    if (value == null || value < 0) {
      throw refuse(at, field + " must be a non-negative integer");
    }
    return value;
  }

  /// The integral value a JSON number denotes, in long range: a revision counter carries no
  /// index, so it is not held to the int range the positions are.
  static Long longValue(final String text) {
    final double value = Double.parseDouble(text);
    // 2^63 is a double and not a long, so the upper bound is exclusive
    if (value != Math.rint(value) || value < -0x1p63 || value >= 0x1p63) {
      return null;
    }
    return (long) value;
  }

  private static long positiveLong(final JsonIterator ji, final String at, final String field) {
    if (ji.whatIsNext() != ValueType.NUMBER) {
      ji.skip();
      throw refuse(at, field + " must be a non-negative integer");
    }
    final var value = longValue(ji.readNumberAsString());
    if (value == null || value < 0) {
      throw refuse(at, field + " must be a non-negative integer");
    }
    if (value == 0) {
      throw refuse(at, field + " must be a positive integer");
    }
    return value;
  }

  private static void expectArray(final JsonIterator ji, final String at, final String field) {
    if (ji.whatIsNext() != ValueType.ARRAY) {
      ji.skip();
      throw refuse(at, field + " must be an array");
    }
  }

  private static byte[] byteArray(final JsonIterator ji, final String at, final String field) {
    expectArray(ji, at, field);
    final var bytes = new java.io.ByteArrayOutputStream();
    String error = null;
    int i = 0;
    while (ji.readArray()) {
      if (ji.whatIsNext() != ValueType.NUMBER) {
        ji.skip();
        error = field + "[" + i + "] is not a byte";
        break;
      }
      final var value = integerValue(ji.readNumberAsString());
      if (value == null || value < 0 || value > 255) {
        error = field + "[" + i + "] is not a byte";
        break;
      }
      bytes.write(value);
      ++i;
    }
    if (error != null) {
      // the array is drained past the malformed element, so the enclosing object read
      // continues where it ends and the refusal is raised whole
      while (ji.readArray()) {
        ji.skip();
      }
      throw refuse(at, error);
    }
    return bytes.toByteArray();
  }

  private static String unknownField(final char[] buf, final int offset, final int len) {
    return "unknown field \"" + new String(buf, offset, len) + "\"";
  }

  private static String format(final Discriminator discriminator) {
    final var data = discriminator.data();
    final var sb = new StringBuilder("[");
    for (int i = 0; i < data.length; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(data[i] & 0xff);
    }
    return sb.append(']').toString();
  }


  /// No discriminator may be a prefix of another's: matching is by prefix, so the shorter
  /// would shadow the longer.
  static void checkShadowing(final List<InstructionEntry> entries, final String at) {
    // each discriminator's bytes once, not once per pair
    final byte[][] data = new byte[entries.size()][];
    for (int i = 0; i < data.length; i++) {
      data[i] = entries.get(i).discriminator().data();
    }
    for (int i = 0; i < entries.size(); i++) {
      final var a = entries.get(i);
      for (int j = i + 1; j < entries.size(); j++) {
        final var b = entries.get(j);
        final InstructionEntry shorter, longer;
        final byte[] shorterData, longerData;
        if (data[i].length <= data[j].length) {
          shorter = a;
          longer = b;
          shorterData = data[i];
          longerData = data[j];
        } else {
          shorter = b;
          longer = a;
          shorterData = data[j];
          longerData = data[i];
        }
        if (isPrefix(shorterData, longerData)) {
          throw refuse(at, "discriminator of " + shorter.name() + " (" + format(shorter.discriminator())
              + ") is a prefix of " + longer.name() + "'s and would shadow it");
        }
      }
    }
  }

  /// The checks across a document's entries that a document built from records still owes
  /// before a mapper reads it: every seat and position in range and in order, no shadowing
  /// discriminator. Each record checked its own fields on construction, and a parsed
  /// document passed all of this already.
  static void checkDocument(final MappingDocument document) {
    final var at = document.programId().toBase58();
    final var entries = document.instructions();
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i) instanceof InstructionEntry.Mapped mapped) {
        validateShape(mapped.sourceAccounts(), mapped.destinationAccounts(), at + " instructions[" + i + "]");
      }
    }
    checkShadowing(entries, at);
  }

  /// Callers pass the shorter discriminator first.
  private static boolean isPrefix(final byte[] shorter, final byte[] longer) {
    for (int i = 0; i < shorter.length; i++) {
      if (shorter[i] != longer[i]) {
        return false;
      }
    }
    return true;
  }

  // The builders: one per JSON object shape. Each records the first unknown field as it is
  // read and, once the object closes, refuses it, then checks presence and shape, in the
  // contract's order.

  private static final class DocumentBuilder implements FieldBufferPredicate {

    private final String label;
    private boolean schemaVersionSeen;
    private Integer schemaVersionValue;
    private String schemaVersionText;
    private String environment;
    private PublicKey programId, proxyProgramId;
    private String programIdError, proxyProgramIdError;
    private boolean provenanceSeen;
    private ProvenanceBuilder provenance;
    private List<EntryBuilder> instructions;
    private String unknownField, duplicateField;

    private final Set<String> fields = new HashSet<>();

    private DocumentBuilder(final String label) {
      this.label = label;
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      final var field = new String(buf, offset, len);
      if (!fields.add(field)) {
        if (duplicateField == null) {
          duplicateField = field;
        }
        ji.skip();
        return true;
      }
      if (fieldEquals("schema_version", buf, offset, len)) {
        schemaVersionSeen = true;
        if (ji.whatIsNext() == ValueType.NUMBER) {
          final var text = ji.readNumberAsString();
          schemaVersionValue = integerValue(text);
          schemaVersionText = plainNumber(text);
        } else {
          schemaVersionText = describeSkipped(ji);
        }
      } else if (fieldEquals("environment", buf, offset, len)) {
        try {
          environment = nonBlankString(ji, "", "environment");
        } catch (final MappingDocumentException ignored) {
          // absent and malformed read the same: the build names the field once
        }
      } else if (fieldEquals("program_id", buf, offset, len)) {
        try {
          programId = address(ji, "", "program_id");
        } catch (final MappingDocumentException e) {
          programIdError = e.detail();
        }
      } else if (fieldEquals("proxy_program_id", buf, offset, len)) {
        try {
          proxyProgramId = address(ji, "", "proxy_program_id");
        } catch (final MappingDocumentException e) {
          proxyProgramIdError = e.detail();
        }
      } else if (fieldEquals("provenance", buf, offset, len)) {
        provenanceSeen = true;
        // checked once program_id is known, since its messages name the program
        provenance = new ProvenanceBuilder();
        provenance.read(ji);
      } else if (fieldEquals("instructions", buf, offset, len)) {
        instructions = new ArrayList<>();
        if (ji.whatIsNext() != ValueType.ARRAY) {
          ji.skip();
          instructions = null;
          return true;
        }
        int i = 0;
        while (ji.readArray()) {
          final var entry = new EntryBuilder(i);
          entry.read(ji);
          instructions.add(entry);
          ++i;
        }
      } else {
        if (unknownField == null) {
          unknownField = unknownField(buf, offset, len);
        }
        ji.skip();
      }
      return true;
    }

    /// The value as `String(value)` prints it: a string as it is, a number as [#plainNumber],
    /// an array as its elements joined by commas (null and empty arrays as nothing), an object
    /// as `[object Object]`.
    private static String describeSkipped(final JsonIterator ji) {
      return switch (ji.whatIsNext()) {
        case STRING -> ji.readString();
        case NUMBER -> plainNumber(ji.readNumberAsString());
        case BOOLEAN -> String.valueOf(ji.readBoolean());
        case NULL -> {
          ji.readNull();
          yield "null";
        }
        case ARRAY -> {
          final var sb = new StringBuilder();
          boolean first = true;
          while (ji.readArray()) {
            if (!first) {
              sb.append(',');
            }
            first = false;
            // a null element prints as nothing; the string "null" prints as itself
            if (ji.whatIsNext() == ValueType.NULL) {
              ji.readNull();
            } else {
              sb.append(describeSkipped(ji));
            }
          }
          yield sb.toString();
        }
        default -> {
          ji.skip();
          yield "[object Object]";
        }
      };
    }

    private MappingDocument build() {
      // the checks run in the contract's order once the object closes, whatever the order of
      // its members: a field named twice, an unknown field, the version, then each field
      if (duplicateField != null) {
        throw refuse(label, "duplicate field \"" + duplicateField + "\"");
      }
      if (unknownField != null) {
        throw refuse(label, unknownField);
      }
      // the version is the number 1, however the number is spelled; an absent version has no
      // value
      if (schemaVersionValue == null || schemaVersionValue != MappingDocument.SCHEMA_VERSION) {
        throw refuse(label, "schema_version " + (schemaVersionSeen ? schemaVersionText : "undefined")
            + " is not " + MappingDocument.SCHEMA_VERSION);
      }
      if (environment == null) {
        throw refuse(label, "environment must be a non-blank string");
      }
      if (programIdError != null) {
        throw refuse(label, programIdError);
      }
      if (programId == null) {
        throw refuse(label, "program_id must be a non-blank string");
      }
      if (proxyProgramIdError != null) {
        throw refuse(label, proxyProgramIdError);
      }
      if (proxyProgramId == null) {
        throw refuse(label, "proxy_program_id must be a non-blank string");
      }
      final var at = label + " " + programId.toBase58();
      final Provenance provenanceRecord = provenanceSeen ? provenance.build(at + " provenance") : null;
      if (instructions == null) {
        throw refuse(at, "instructions must be an array");
      }
      final var entries = new ArrayList<InstructionEntry>(instructions.size());
      for (final var builder : instructions) {
        entries.add(builder.build(at + " instructions[" + builder.index + "]"));
      }
      checkShadowing(entries, at);
      return new MappingDocument(
          MappingDocument.SCHEMA_VERSION,
          environment,
          programId,
          proxyProgramId,
          provenanceRecord,
          List.copyOf(entries)
      );
    }
  }

  /// Provenance is read before the program id may be known, so its refusals are raised at
  /// build time with the document's `at`, in the contract's order.
  private static final class ProvenanceBuilder {

    private final Set<String> fields = new HashSet<>();
    private String duplicateField;

    private boolean malformed;
    private String unknownField;
    private String generator, sourceIdl, proxyIdl;
    private String generatorError, sourceIdlError, proxyIdlError, configRevisionError;
    private Long configRevision;

    private void read(final JsonIterator ji) {
      if (ji.whatIsNext() != ValueType.OBJECT) {
        ji.skip();
        malformed = true;
        return;
      }
      ji.testObject((buf, offset, len, iterator) -> {
        final var field = new String(buf, offset, len);
        if (!fields.add(field)) {
          // a document that names a field twice is refused, so no value is read twice into a
          // half-updated holder
          if (duplicateField == null) {
            duplicateField = field;
          }
          iterator.skip();
          return true;
        }
        if (fieldEquals("generator", buf, offset, len)) {
          try {
            generator = nonBlankString(iterator, "", "generator");
          } catch (final MappingDocumentException e) {
            generatorError = e.detail();
          }
        } else if (fieldEquals("source_idl", buf, offset, len)) {
          try {
            sourceIdl = nonBlankString(iterator, "", "source_idl");
          } catch (final MappingDocumentException e) {
            sourceIdlError = e.detail();
          }
        } else if (fieldEquals("proxy_idl", buf, offset, len)) {
          try {
            proxyIdl = nonBlankString(iterator, "", "proxy_idl");
          } catch (final MappingDocumentException e) {
            proxyIdlError = e.detail();
          }
        } else if (fieldEquals("config_revision", buf, offset, len)) {
          try {
            configRevision = positiveLong(iterator, "", "config_revision");
          } catch (final MappingDocumentException e) {
            configRevisionError = e.detail();
          }
        } else if (unknownField == null) {
          unknownField = unknownField(buf, offset, len);
          iterator.skip();
        } else {
          iterator.skip();
        }
        return true;
      });
    }

    private Provenance build(final String at) {
      if (malformed) {
        throw refuse(at, "must be an object");
      }
      if (duplicateField != null) {
        throw refuse(at, "duplicate field \"" + duplicateField + "\"");
      }
      if (unknownField != null) {
        throw refuse(at, unknownField);
      }
      if (configRevisionError != null) {
        throw refuse(at, configRevisionError);
      }
      if (generatorError != null) {
        throw refuse(at, generatorError);
      }
      if (sourceIdlError != null) {
        throw refuse(at, sourceIdlError);
      }
      if (proxyIdlError != null) {
        throw refuse(at, proxyIdlError);
      }
      return new Provenance(generator, sourceIdl, proxyIdl, configRevision == null ? 1L : configRevision);
    }
  }

  /// An entry's fields are read into holders and checked once the document's `at` is known,
  /// so a refusal names the entry the way the contract does.
  private static final class EntryBuilder {

    private final Set<String> fields = new HashSet<>();
    private String duplicateField;

    private final int index;
    private String unknownField;
    private boolean malformed;
    private String name;
    private byte[] discriminator;
    private String discriminatorError;
    private boolean dispositionSeen, dispositionMalformed;
    private String disposition;
    private String reason;
    private boolean reasonSeen, handlerSeen, sourcesSeen, seatsSeen, remainingSeen;
    private HandlerBuilder handler;
    private List<SourceBuilder> sources;
    private List<SeatBuilder> seats;
    private RemainingAccounts remaining;
    private String remainingError;

    private EntryBuilder(final int index) {
      this.index = index;
    }

    private void read(final JsonIterator ji) {
      if (ji.whatIsNext() != ValueType.OBJECT) {
        ji.skip();
        malformed = true;
        return;
      }
      ji.testObject((buf, offset, len, iterator) -> {
        final var field = new String(buf, offset, len);
        if (!fields.add(field)) {
          // a document that names a field twice is refused, so no value is read twice into a
          // half-updated holder
          if (duplicateField == null) {
            duplicateField = field;
          }
          iterator.skip();
          return true;
        }
        if (fieldEquals("name", buf, offset, len)) {
          try {
            name = nonBlankString(iterator, "", "name");
          } catch (final MappingDocumentException ignored) {
            // absent and malformed read the same: the build names the field once
          }
        } else if (fieldEquals("discriminator", buf, offset, len)) {
          try {
            discriminator = byteArray(iterator, "", "discriminator");
          } catch (final MappingDocumentException e) {
            discriminatorError = e.detail();
          }
        } else if (fieldEquals("disposition", buf, offset, len)) {
          dispositionSeen = true;
          if (iterator.whatIsNext() == ValueType.STRING) {
            disposition = iterator.readString();
          } else {
            dispositionMalformed = true;
            disposition = DocumentBuilder.describeSkipped(iterator);
          }
        } else if (fieldEquals("reason", buf, offset, len)) {
          reasonSeen = true;
          try {
            reason = nonBlankString(iterator, "", "reason");
          } catch (final MappingDocumentException ignored) {
            // absent and malformed read the same: the build names the field once
          }
        } else if (fieldEquals("handler", buf, offset, len)) {
          handlerSeen = true;
          handler = new HandlerBuilder();
          handler.read(iterator);
        } else if (fieldEquals("source_accounts", buf, offset, len)) {
          sourcesSeen = true;
          if (iterator.whatIsNext() != ValueType.ARRAY) {
            iterator.skip();
          } else {
            sources = new ArrayList<>();
            int i = 0;
            while (iterator.readArray()) {
              final var source = new SourceBuilder(i);
              source.read(iterator);
              sources.add(source);
              ++i;
            }
          }
        } else if (fieldEquals("destination_accounts", buf, offset, len)) {
          seatsSeen = true;
          if (iterator.whatIsNext() != ValueType.ARRAY) {
            iterator.skip();
          } else {
            seats = new ArrayList<>();
            int i = 0;
            while (iterator.readArray()) {
              final var seat = new SeatBuilder(i);
              seat.read(iterator);
              seats.add(seat);
              ++i;
            }
          }
        } else if (fieldEquals("remaining_accounts", buf, offset, len)) {
          remainingSeen = true;
          if (iterator.whatIsNext() != ValueType.OBJECT) {
            iterator.skip();
            remainingError = "must be an object";
          } else {
            final var kind = new String[1];
            final var kindMalformed = new boolean[1];
            final var unknown = new String[1];
            final var duplicate = new String[1];
            final var seen = new HashSet<String>();
            iterator.testObject((b, o, l, it) -> {
              final var ruleField = new String(b, o, l);
              if (!seen.add(ruleField)) {
                if (duplicate[0] == null) {
                  duplicate[0] = ruleField;
                }
                it.skip();
                return true;
              }
              if (fieldEquals("kind", b, o, l)) {
                if (it.whatIsNext() == ValueType.STRING) {
                  kind[0] = it.readString();
                } else {
                  kindMalformed[0] = true;
                  kind[0] = DocumentBuilder.describeSkipped(it);
                }
              } else {
                if (unknown[0] == null) {
                  unknown[0] = unknownField(b, o, l);
                }
                it.skip();
              }
              return true;
            });
            if (duplicate[0] != null) {
              remainingError = "duplicate field \"" + duplicate[0] + "\"";
            } else if (unknown[0] != null) {
              remainingError = unknown[0];
            } else {
              remaining = kindMalformed[0] ? null : RemainingAccounts.fromJsonName(kind[0]);
              if (remaining == null) {
                remainingError = "unknown remaining-accounts kind " + (kind[0] == null ? "undefined" : kind[0]);
              }
            }
          }
        } else {
          if (unknownField == null) {
            unknownField = unknownField(buf, offset, len);
          }
          iterator.skip();
        }
        return true;
      });
    }

    private InstructionEntry build(final String at) {
      if (malformed) {
        throw refuse(at, "must be an object");
      }
      if (duplicateField != null) {
        throw refuse(at, "duplicate field \"" + duplicateField + "\"");
      }
      if (unknownField != null) {
        throw refuse(at, unknownField);
      }
      if (name == null) {
        throw refuse(at, "name must be a non-blank string");
      }
      if (discriminatorError != null) {
        throw refuse(at, discriminatorError);
      }
      if (discriminator == null) {
        throw refuse(at, "discriminator must be an array");
      }
      if (discriminator.length == 0) {
        throw refuse(at, name + " has no discriminator");
      }
      final var discriminatorRecord = Discriminator.createDiscriminator(discriminator);
      if (!dispositionSeen) {
        throw refuse(at, name + " has no disposition");
      }
      if (dispositionMalformed) {
        throw refuse(at, name + " has unknown disposition " + disposition);
      }
      switch (disposition) {
        case "passthrough", "unsupported" -> {
          final var what = "a " + disposition + " entry";
          if (handlerSeen) {
            throw refuse(at, what + " carries no \"handler\"");
          }
          if (sourcesSeen) {
            throw refuse(at, what + " carries no \"source_accounts\"");
          }
          if (seatsSeen) {
            throw refuse(at, what + " carries no \"destination_accounts\"");
          }
          if (remainingSeen) {
            throw refuse(at, what + " carries no \"remaining_accounts\"");
          }
          if (reason == null) {
            throw refuse(at, "reason must be a non-blank string");
          }
          return disposition.equals("passthrough")
              ? new InstructionEntry.Passthrough(name, discriminatorRecord, reason)
              : new InstructionEntry.Unsupported(name, discriminatorRecord, reason);
        }
        case "map" -> {
          if (reasonSeen) {
            throw refuse(at, "a map entry carries no \"reason\"");
          }
          final var handlerRecord = handlerSeen ? handler.build(at + " handler") : null;
          if (handlerRecord == null) {
            throw refuse(at + " handler", "must be an object");
          }
          if (sources == null) {
            throw refuse(at, "source_accounts must be an array");
          }
          if (seats == null) {
            throw refuse(at, "destination_accounts must be an array");
          }
          final var sourceAccounts = new ArrayList<SourceAccount>(sources.size());
          for (final var source : sources) {
            sourceAccounts.add(source.build(at + " source_accounts[" + source.index + "]"));
          }
          final var destinationAccounts = new ArrayList<DestinationAccount>(seats.size());
          for (final var seat : seats) {
            destinationAccounts.add(seat.build(at + " destination_accounts[" + seat.index + "]"));
          }
          if (remainingError != null) {
            throw refuse(at + " remaining_accounts", remainingError);
          }
          final var remainingAccounts = remainingSeen ? remaining : RemainingAccounts.ANY;
          validateShape(sourceAccounts, destinationAccounts, at);
          return new InstructionEntry.Mapped(
              name,
              discriminatorRecord,
              handlerRecord,
              List.copyOf(sourceAccounts),
              List.copyOf(destinationAccounts),
              remainingAccounts
          );
        }
        case null -> throw refuse(at, name + " has unknown disposition null");
        default -> throw refuse(at, name + " has unknown disposition " + disposition);
      }
    }
  }

  private static final class HandlerBuilder {

    private final Set<String> fields = new HashSet<>();
    private String duplicateField;

    private boolean malformed;
    private String unknownField;
    private String name;
    private byte[] discriminator;
    private String discriminatorError;

    private void read(final JsonIterator ji) {
      if (ji.whatIsNext() != ValueType.OBJECT) {
        ji.skip();
        malformed = true;
        return;
      }
      ji.testObject((buf, offset, len, iterator) -> {
        final var field = new String(buf, offset, len);
        if (!fields.add(field)) {
          // a document that names a field twice is refused, so no value is read twice into a
          // half-updated holder
          if (duplicateField == null) {
            duplicateField = field;
          }
          iterator.skip();
          return true;
        }
        if (fieldEquals("name", buf, offset, len)) {
          try {
            name = nonBlankString(iterator, "", "name");
          } catch (final MappingDocumentException ignored) {
            // absent and malformed read the same: the build names the field once
          }
        } else if (fieldEquals("discriminator", buf, offset, len)) {
          try {
            discriminator = byteArray(iterator, "", "discriminator");
          } catch (final MappingDocumentException e) {
            discriminatorError = e.detail();
          }
        } else {
          if (unknownField == null) {
            unknownField = unknownField(buf, offset, len);
          }
          iterator.skip();
        }
        return true;
      });
    }

    private Handler build(final String at) {
      if (malformed) {
        return null;
      }
      if (duplicateField != null) {
        throw refuse(at, "duplicate field \"" + duplicateField + "\"");
      }
      if (unknownField != null) {
        throw refuse(at, unknownField);
      }
      if (discriminatorError != null) {
        throw refuse(at, discriminatorError);
      }
      if (discriminator == null) {
        throw refuse(at, "discriminator must be an array");
      }
      if (discriminator.length == 0) {
        throw refuse(at, "the handler has no discriminator");
      }
      if (name == null) {
        throw refuse(at, "name must be a non-blank string");
      }
      return new Handler(name, Discriminator.createDiscriminator(discriminator));
    }
  }

  private static final class SourceBuilder {

    private final Set<String> fields = new HashSet<>();
    private String duplicateField;

    private final int index;
    private boolean malformed;
    private String unknownField;
    private String name;
    private Boolean writable, signer;
    private boolean dynamicSignerSeen, dynamicSigner;
    private boolean optionalSeen, optionalMalformed;
    private String optionalText;
    private boolean expectSeen;
    private String expect, expectError;

    private SourceBuilder(final int index) {
      this.index = index;
    }

    private void read(final JsonIterator ji) {
      if (ji.whatIsNext() != ValueType.OBJECT) {
        ji.skip();
        malformed = true;
        return;
      }
      ji.testObject((buf, offset, len, iterator) -> {
        final var field = new String(buf, offset, len);
        if (!fields.add(field)) {
          // a document that names a field twice is refused, so no value is read twice into a
          // half-updated holder
          if (duplicateField == null) {
            duplicateField = field;
          }
          iterator.skip();
          return true;
        }
        if (fieldEquals("name", buf, offset, len)) {
          try {
            name = nonBlankString(iterator, "", "name");
          } catch (final MappingDocumentException ignored) {
            // absent and malformed read the same: the build names the field once
          }
        } else if (fieldEquals("writable", buf, offset, len)) {
          try {
            writable = bool(iterator, "", "writable");
          } catch (final MappingDocumentException ignored) {
            // absent and malformed read the same: the build names the field once
          }
        } else if (fieldEquals("signer", buf, offset, len)) {
          try {
            signer = bool(iterator, "", "signer");
          } catch (final MappingDocumentException ignored) {
            // absent and malformed read the same: the build names the field once
          }
        } else if (fieldEquals("dynamic_signer", buf, offset, len)) {
          dynamicSignerSeen = true;
          if (iterator.whatIsNext() == ValueType.BOOLEAN) {
            dynamicSigner = iterator.readBoolean();
          } else {
            iterator.skip();
            dynamicSigner = false;
          }
        } else if (fieldEquals("optional", buf, offset, len)) {
          optionalSeen = true;
          if (iterator.whatIsNext() == ValueType.STRING) {
            optionalText = iterator.readString();
          } else {
            optionalMalformed = true;
            optionalText = DocumentBuilder.describeSkipped(iterator);
          }
        } else if (fieldEquals("expect", buf, offset, len)) {
          expectSeen = true;
          try {
            expect = nonBlankString(iterator, "", "expect");
          } catch (final MappingDocumentException e) {
            expectError = e.detail();
          }
        } else {
          if (unknownField == null) {
            unknownField = unknownField(buf, offset, len);
          }
          iterator.skip();
        }
        return true;
      });
    }

    private SourceAccount build(final String at) {
      if (malformed) {
        throw refuse(at, "must be an object");
      }
      if (duplicateField != null) {
        throw refuse(at, "duplicate field \"" + duplicateField + "\"");
      }
      if (unknownField != null) {
        throw refuse(at, unknownField);
      }
      if (name == null) {
        throw refuse(at, "name must be a non-blank string");
      }
      if (writable == null) {
        throw refuse(at, "writable must be a boolean");
      }
      if (signer == null) {
        throw refuse(at, "signer must be a boolean");
      }
      if (dynamicSignerSeen && !dynamicSigner) {
        throw refuse(at, "dynamic_signer must be true when present");
      }
      OptionalKind optional = null;
      if (optionalSeen) {
        optional = optionalMalformed ? null : OptionalKind.fromJsonName(optionalText);
        if (optional == null) {
          throw refuse(at, "unknown optional kind " + optionalText);
        }
      }
      Expectation expectation = null;
      if (expectSeen) {
        if (expectError != null) {
          throw refuse(at, expectError);
        }
        final var dynamicName = DynamicAccountName.fromJsonName(expect);
        if (dynamicName != null) {
          expectation = new Expectation.Dynamic(dynamicName);
        } else {
          final var address = decodeAddress(expect);
          if (address == null) {
            throw refuse(at, "expect " + expect + " is neither a dynamic account nor an address");
          }
          expectation = new Expectation.Address(address);
        }
      }
      return new SourceAccount(name, writable, signer, dynamicSignerSeen, optional, expectation);
    }
  }

  private static final class SeatBuilder {

    private final Set<String> fields = new HashSet<>();
    private String duplicateField;

    private final int index;
    private boolean malformed;
    private String unknownField;
    private Integer seatIndex;
    private boolean kindSeen, kindMalformed;
    private String kind;
    private boolean nameSeen;
    private String name;
    private boolean addressSeen;
    private PublicKey address;
    private String addressError;
    private boolean sourceSeen;
    private Integer source;
    private Boolean writable, signer;
    private boolean sentinelSeen, sentinel;

    private SeatBuilder(final int index) {
      this.index = index;
    }

    private void read(final JsonIterator ji) {
      if (ji.whatIsNext() != ValueType.OBJECT) {
        ji.skip();
        malformed = true;
        return;
      }
      ji.testObject((buf, offset, len, iterator) -> {
        final var field = new String(buf, offset, len);
        if (!fields.add(field)) {
          // a document that names a field twice is refused, so no value is read twice into a
          // half-updated holder
          if (duplicateField == null) {
            duplicateField = field;
          }
          iterator.skip();
          return true;
        }
        if (fieldEquals("index", buf, offset, len)) {
          try {
            seatIndex = nonNegativeInteger(iterator, "", "index");
          } catch (final MappingDocumentException ignored) {
            // absent and malformed read the same: the build names the field once
          }
        } else if (fieldEquals("kind", buf, offset, len)) {
          kindSeen = true;
          if (iterator.whatIsNext() == ValueType.STRING) {
            kind = iterator.readString();
          } else {
            kindMalformed = true;
            kind = DocumentBuilder.describeSkipped(iterator);
          }
        } else if (fieldEquals("name", buf, offset, len)) {
          nameSeen = true;
          try {
            name = nonBlankString(iterator, "", "name");
          } catch (final MappingDocumentException ignored) {
            // absent and malformed read the same: the build names the field once
          }
        } else if (fieldEquals("address", buf, offset, len)) {
          addressSeen = true;
          try {
            address = address(iterator, "", "address");
          } catch (final MappingDocumentException e) {
            addressError = e.detail();
          }
        } else if (fieldEquals("source", buf, offset, len)) {
          sourceSeen = true;
          try {
            source = nonNegativeInteger(iterator, "", "source");
          } catch (final MappingDocumentException ignored) {
            // absent and malformed read the same: the build names the field once
          }
        } else if (fieldEquals("writable", buf, offset, len)) {
          try {
            writable = bool(iterator, "", "writable");
          } catch (final MappingDocumentException ignored) {
            // absent and malformed read the same: the build names the field once
          }
        } else if (fieldEquals("signer", buf, offset, len)) {
          try {
            signer = bool(iterator, "", "signer");
          } catch (final MappingDocumentException ignored) {
            // absent and malformed read the same: the build names the field once
          }
        } else if (fieldEquals("sentinel", buf, offset, len)) {
          sentinelSeen = true;
          if (iterator.whatIsNext() == ValueType.BOOLEAN) {
            sentinel = iterator.readBoolean();
          } else {
            iterator.skip();
            sentinel = false;
          }
        } else {
          if (unknownField == null) {
            unknownField = unknownField(buf, offset, len);
          }
          iterator.skip();
        }
        return true;
      });
    }

    private DestinationAccount build(final String at) {
      if (malformed) {
        throw refuse(at, "must be an object");
      }
      if (duplicateField != null) {
        throw refuse(at, "duplicate field \"" + duplicateField + "\"");
      }
      if (unknownField != null) {
        throw refuse(at, unknownField);
      }
      if (seatIndex == null) {
        throw refuse(at, "index must be a non-negative integer");
      }
      if (writable == null) {
        throw refuse(at, "writable must be a boolean");
      }
      if (signer == null) {
        throw refuse(at, "signer must be a boolean");
      }
      if (!kindSeen) {
        throw refuse(at, "the seat has no kind");
      }
      if (kindMalformed) {
        throw refuse(at, "unknown seat kind " + kind);
      }
      switch (kind) {
        case "dynamic" -> {
          if (addressSeen) {
            throw refuse(at, "a dynamic seat carries no \"address\"");
          }
          if (sourceSeen) {
            throw refuse(at, "a dynamic seat carries no \"source\"");
          }
          if (sentinelSeen) {
            throw refuse(at, "a dynamic seat carries no \"sentinel\"");
          }
          if (name == null) {
            throw refuse(at, "name must be a non-blank string");
          }
          final var dynamicName = DynamicAccountName.fromJsonName(name);
          if (dynamicName == null) {
            throw refuse(at, "unknown dynamic account " + name);
          }
          return new DestinationAccount.Dynamic(seatIndex, dynamicName, writable, signer);
        }
        case "static" -> {
          if (nameSeen) {
            throw refuse(at, "a static seat carries no \"name\"");
          }
          if (sourceSeen) {
            throw refuse(at, "a static seat carries no \"source\"");
          }
          if (sentinelSeen) {
            throw refuse(at, "a static seat carries no \"sentinel\"");
          }
          if (addressError != null) {
            throw refuse(at, addressError);
          }
          if (address == null) {
            throw refuse(at, "address must be a non-blank string");
          }
          return new DestinationAccount.Static(seatIndex, address, writable, signer);
        }
        case "source" -> {
          if (nameSeen) {
            throw refuse(at, "a source seat carries no \"name\"");
          }
          if (addressSeen) {
            throw refuse(at, "a source seat carries no \"address\"");
          }
          if (source == null) {
            throw refuse(at, "source must be a non-negative integer");
          }
          if (sentinelSeen && !sentinel) {
            throw refuse(at, "sentinel must be true when present");
          }
          return new DestinationAccount.Source(seatIndex, source, writable, signer, sentinelSeen);
        }
        case null -> throw refuse(at, "unknown seat kind null");
        default -> throw refuse(at, "unknown seat kind " + kind);
      }
    }
  }

  /// The invariants over a map entry's positions and seats.
  private static void validateShape(final List<SourceAccount> sources,
                                    final List<DestinationAccount> seats,
                                    final String at) {
    final var seen = new boolean[seats.size()];
    final var forwarded = new boolean[sources.size()];
    for (final var seat : seats) {
      if (seat.index() >= seats.size()) {
        throw refuse(at, "seats are not dense from 0: seat " + seat.index() + " of " + seats.size());
      }
      if (seen[seat.index()]) {
        throw refuse(at, "seat " + seat.index() + " is listed twice");
      }
      seen[seat.index()] = true;
      if (seat instanceof DestinationAccount.Source forward) {
        if (forward.source() >= sources.size()) {
          throw refuse(at, "seat " + seat.index() + " forwards source position " + forward.source()
              + ", which is out of range of " + sources.size());
        }
        if (forwarded[forward.source()]) {
          throw refuse(at, "seat " + seat.index() + " forwards source position " + forward.source()
              + ", which is already forwarded");
        }
        forwarded[forward.source()] = true;
        if (!seat.writable() && sources.get(forward.source()).writable()) {
          // the handler passes the account on with the flags it received, so a read-only seat
          // would deny the native program a write its own IDL declares
          throw refuse(at, "seat " + seat.index() + " forwards source position " + forward.source()
              + " read-only, which the native instruction declares writable");
        }
        if (forward.sentinel() && sources.get(forward.source()).optional() != OptionalKind.PROGRAM_ID) {
          throw refuse(at, "seat " + seat.index() + " rewrites a sentinel, but source position " + forward.source()
              + " is not an optional the client passes as the program id");
        }
      }
    }
    boolean omittedSeen = false;
    for (int i = 0; i < sources.size(); i++) {
      if (sources.get(i).optional() == OptionalKind.OMITTED) {
        omittedSeen = true;
      } else if (omittedSeen) {
        throw refuse(at, "source position " + i + " follows an omittable optional; a client that leaves it out shifts this position");
      }
    }
    // Omittable seats come last, and in source order: a client may leave out only a trailing
    // run of positions, so an absent position must never be followed by a present seat.
    final var ordered = new ArrayList<>(seats);
    ordered.sort(java.util.Comparator.comparingInt(DestinationAccount::index));
    boolean omittableSeen = false;
    int lastOmittableSource = -1;
    for (final var seat : ordered) {
      final boolean omittable = seat instanceof DestinationAccount.Source forward
          && sources.get(forward.source()).optional() == OptionalKind.OMITTED;
      if (omittable) {
        omittableSeen = true;
        final int source = ((DestinationAccount.Source) seat).source();
        if (source <= lastOmittableSource) {
          throw refuse(at, "seat " + seat.index() + " forwards omittable position " + source
              + " after position " + lastOmittableSource + "; an absent run would shift it");
        }
        lastOmittableSource = source;
      } else if (omittableSeen) {
        throw refuse(at, "seat " + seat.index() + " follows a seat a client may leave out; an absent one would shift it");
      }
    }
  }
}
