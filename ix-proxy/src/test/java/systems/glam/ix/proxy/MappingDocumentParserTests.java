package systems.glam.ix.proxy;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import software.sava.core.accounts.PublicKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/// The parser's admission and refusals: the contract's valid document, its patches and its
/// messages, row for row, then the rows a streaming parser adds.
final class MappingDocumentParserTests {

  private static final String PROGRAM = "Src1111111111111111111111111111111111111111";
  private static final String PROXY = "Proxy11111111111111111111111111111111111111";

  private static Map<String, Object> map(final Object... pairs) {
    final var map = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], pairs[i + 1]);
    }
    return map;
  }

  private static List<Object> list(final Object... values) {
    final var list = new ArrayList<>(values.length);
    java.util.Collections.addAll(list, values);
    return list;
  }

  static Map<String, Object> valid() {
    return map(
        "schema_version", 1L,
        "environment", "test",
        "program_id", PROGRAM,
        "proxy_program_id", PROXY,
        "provenance", map("generator", "g", "config_revision", 1L),
        "instructions", list(
            map(
                "name", "do",
                "discriminator", list(1L, 2L),
                "disposition", "map",
                "handler", map("name", "proxy_do", "discriminator", list(9L)),
                "source_accounts", list(
                    map("name", "owner", "writable", true, "signer", true, "expect", "glam_vault"),
                    map("name", "thing", "writable", true, "signer", false),
                    map("name", "maybe", "writable", false, "signer", false, "optional", "program_id"),
                    map("name", "trailing", "writable", false, "signer", false, "optional", "omitted")
                ),
                "destination_accounts", list(
                    map("index", 0L, "kind", "dynamic", "name", "glam_vault", "writable", true, "signer", false),
                    map("index", 1L, "kind", "static", "address", PROXY, "writable", false, "signer", false),
                    map("index", 2L, "kind", "source", "source", 1L, "writable", true, "signer", false),
                    map("index", 3L, "kind", "source", "source", 2L, "writable", false, "signer", false, "sentinel", true),
                    map("index", 4L, "kind", "source", "source", 3L, "writable", false, "signer", false)
                ),
                "remaining_accounts", map("kind", "any")
            ),
            map("name", "read", "discriminator", list(3L), "disposition", "passthrough", "reason", "nothing signs"),
            map("name", "other", "discriminator", list(4L), "disposition", "unsupported", "reason", "no handler")
        )
    );
  }

  private static Map<String, Object> entry(final Map<String, Object> document, final int i) {
    return Json.object(Json.array(document.get("instructions")).get(i));
  }

  private static Map<String, Object> seat(final Map<String, Object> document, final int i) {
    return Json.object(Json.array(entry(document, 0).get("destination_accounts")).get(i));
  }

  private static Map<String, Object> source(final Map<String, Object> document, final int i) {
    return Json.object(Json.array(entry(document, 0).get("source_accounts")).get(i));
  }

  private static MappingDocument parse(final Map<String, Object> document) {
    return MappingDocumentParser.parse(Json.write(document), "mapping document");
  }

  @Test
  void admitsAValidDocumentAndReadsItsDefaults() {
    final var document = parse(valid());
    assertEquals(1, document.schemaVersion());
    assertEquals("test", document.environment());
    assertEquals(PublicKey.fromBase58Encoded(PROGRAM), document.programId());
    assertEquals(PublicKey.fromBase58Encoded(PROXY), document.proxyProgramId());
    assertEquals(new Provenance("g", null, null, 1), document.provenance());
    assertEquals(3, document.instructions().size());
    final var mapped = assertInstanceOf(InstructionEntry.Mapped.class, document.instructions().getFirst());
    assertEquals("do", mapped.name());
    assertEquals("proxy_do", mapped.handler().name());
    assertEquals(RemainingAccounts.ANY, mapped.remainingAccounts());
    assertEquals(4, mapped.sourceAccounts().size());
    final var owner = mapped.sourceAccounts().getFirst();
    assertEquals(new Expectation.Dynamic(DynamicAccountName.GLAM_VAULT), owner.expect());
    assertFalse(owner.dynamicSigner());
    assertEquals(OptionalKind.PROGRAM_ID, mapped.sourceAccounts().get(2).optional());
    assertEquals(OptionalKind.OMITTED, mapped.sourceAccounts().get(3).optional());
    final var sentinel = assertInstanceOf(DestinationAccount.Source.class, mapped.destinationAccounts().get(3));
    assertTrue(sentinel.sentinel());
    assertEquals(2, sentinel.source());
    final var fixed = assertInstanceOf(DestinationAccount.Static.class, mapped.destinationAccounts().get(1));
    assertEquals(PublicKey.fromBase58Encoded(PROXY), fixed.address());
    assertInstanceOf(InstructionEntry.Passthrough.class, document.instructions().get(1));
    final var unsupported = assertInstanceOf(InstructionEntry.Unsupported.class, document.instructions().get(2));
    assertEquals("no handler", unsupported.reason());
  }

  @Test
  void aDocumentWithoutProvenanceOrRemainingAccountsTakesTheDefaults() {
    final var document = valid();
    document.remove("provenance");
    entry(document, 0).remove("remaining_accounts");
    final var parsed = parse(document);
    assertNull(parsed.provenance());
    assertEquals(RemainingAccounts.ANY, ((InstructionEntry.Mapped) parsed.instructions().getFirst()).remainingAccounts());
  }

  @Test
  void anAddressMustDecodeToThirtyTwoBytes() {
    // 42 characters of the alphabet, one short of a key: the spelling is fine, the decoding
    // is not
    final var document = valid();
    document.put("program_id", "State1111111111111111111111111111111111111");
    final var e = assertThrows(MappingDocumentException.class, () -> parse(document));
    assertTrue(e.getMessage().contains("program_id is not an address"), e.getMessage());
  }

  @Test
  void aDocumentThatIsNotAnObjectIsRefused() {
    final var e = assertThrows(MappingDocumentException.class, () -> MappingDocumentParser.parse("[]", "documents[0]"));
    assertEquals("documents[0]: must be an object", e.getMessage());
    assertEquals("documents[0]", e.at());
  }

  private record Refusal(String what, Consumer<Map<String, Object>> patch, String message) {
  }

  private static final List<Refusal> REFUSALS = List.of(
      new Refusal("another schema version", d -> d.put("schema_version", 2L), "schema_version 2 is not 1"),
      new Refusal("an unknown top-level field", d -> d.put("index_map", list()), "unknown field \"index_map\""),
      new Refusal("a blank environment", d -> d.put("environment", " "), "environment must be a non-blank string"),
      new Refusal("a program id that is not an address", d -> d.put("program_id", "short"), "program_id is not an address"),
      new Refusal("an unknown provenance field", d -> Json.object(d.get("provenance")).put("commit", "x"), "unknown field \"commit\""),
      new Refusal("a zero config revision", d -> Json.object(d.get("provenance")).put("config_revision", 0L), "config_revision must be a positive integer"),
      new Refusal("an entry without a disposition", d -> entry(d, 1).remove("disposition"), "read has no disposition"),
      new Refusal("an unknown disposition", d -> entry(d, 1).put("disposition", "relay"), "unknown disposition relay"),
      new Refusal("a passthrough without a reason", d -> entry(d, 1).put("reason", ""), "reason must be a non-blank string"),
      new Refusal("a passthrough carrying seats", d -> entry(d, 1).put("destination_accounts", list()), "a passthrough entry carries no \"destination_accounts\""),
      new Refusal("a map entry carrying a reason", d -> entry(d, 0).put("reason", "r"), "a map entry carries no \"reason\""),
      new Refusal("an empty discriminator", d -> entry(d, 2).put("discriminator", list()), "other has no discriminator"),
      new Refusal("a byte out of range", d -> entry(d, 2).put("discriminator", list(256L)), "discriminator[0] is not a byte"),
      new Refusal("a discriminator another's prefixes", d -> entry(d, 2).put("discriminator", list(1L)), "discriminator of other ([1]) is a prefix of do's"),
      new Refusal("a discriminator that prefixes a later one", d -> entry(d, 2).put("discriminator", list(1L, 2L, 3L)), "discriminator of do ([1, 2]) is a prefix of other's"),
      new Refusal("two equal discriminators", d -> entry(d, 2).put("discriminator", list(1L, 2L)), "discriminator of do ([1, 2]) is a prefix of other's"),
      new Refusal("a null config revision", d -> Json.object(d.get("provenance")).put("config_revision", null), "config_revision must be a non-negative integer"),
      new Refusal("a proxy program id that is not an address", d -> d.put("proxy_program_id", "short"), "proxy_program_id is not an address"),
      new Refusal("an address outside the base58 alphabet", d -> d.put("proxy_program_id", "0".repeat(32)), "proxy_program_id is not an address"),
      new Refusal("an address longer than 44 characters", d -> d.put("program_id", "1".repeat(45)), "program_id is not an address"),
      new Refusal("a static seat outside the base58 alphabet", d -> seat(d, 1).put("address", "O".repeat(40)), "address is not an address"),
      new Refusal("a negative byte", d -> entry(d, 2).put("discriminator", list(-1L)), "discriminator[0] is not a byte"),
      new Refusal("a null discriminator byte", d -> Json.object(entry(d, 0).get("handler")).put("discriminator", list((Object) null)), "discriminator[0] is not a byte"),
      new Refusal("a handler without a discriminator", d -> entry(d, 0).put("handler", map("name", "proxy_do", "discriminator", list())), "the handler has no discriminator"),
      new Refusal("a passthrough carrying a handler", d -> entry(d, 1).put("handler", map("name", "h", "discriminator", list(1L))), "a passthrough entry carries no \"handler\""),
      new Refusal("a dynamic seat carrying a sentinel", d -> seat(d, 0).put("sentinel", true), "a dynamic seat carries no \"sentinel\""),
      new Refusal("a source seat carrying a name", d -> seat(d, 2).put("name", "thing"), "a source seat carries no \"name\""),
      new Refusal("a sentinel that is false", d -> seat(d, 3).put("sentinel", false), "sentinel must be true when present"),
      new Refusal("a sentinel on an omitted position", d -> seat(d, 4).put("sentinel", true), "seat 4 rewrites a sentinel, but source position 3 is not an optional the client passes as the program id"),
      new Refusal("a forwarded seat read-only over a writable position", d -> seat(d, 2).put("writable", false), "seat 2 forwards source position 1 read-only, which the native instruction declares writable"),
      new Refusal("a seat index listed twice", d -> seat(d, 4).put("index", 3L), "seat 3 is listed twice"),
      new Refusal("a source position out of range", d -> seat(d, 4).put("source", 9L), "seat 4 forwards source position 9, which is out of range of 4"),
      new Refusal("an unknown source field", d -> source(d, 0).put("index", 0L), "unknown field \"index\""),
      new Refusal("an unknown optional kind", d -> source(d, 2).put("optional", "maybe"), "unknown optional kind maybe"),
      new Refusal("a short expectation", d -> source(d, 0).put("expect", "vault"), "neither a dynamic account nor an address"),
      new Refusal("a dynamic_signer that is false", d -> source(d, 1).put("dynamic_signer", false), "dynamic_signer must be true when present"),
      new Refusal("a seat without a kind", d -> seat(d, 0).remove("kind"), "the seat has no kind"),
      new Refusal("an unknown seat kind", d -> seat(d, 0).put("kind", "fixed"), "unknown seat kind fixed"),
      new Refusal("an unknown dynamic account", d -> seat(d, 0).put("name", "glam_treasury"), "unknown dynamic account glam_treasury"),
      new Refusal("a static seat with a source", d -> seat(d, 1).put("source", 0L), "a static seat carries no \"source\""),
      new Refusal("a gap in the seats", d -> seat(d, 4).put("index", 7L), "seats are not dense from 0"),
      new Refusal("a position forwarded twice", d -> seat(d, 4).put("source", 1L), "already forwarded"),
      new Refusal("a sentinel on a required position", d -> seat(d, 2).put("sentinel", true), "rewrites a sentinel"),
      new Refusal("an omittable optional ahead of a required position", d -> source(d, 1).put("optional", "omitted"), "follows an omittable optional"),
      new Refusal("a seat after an omittable seat", d -> {
        seat(d, 4).put("index", 2L);
        seat(d, 2).put("index", 4L);
      }, "follows a seat a client may leave out"),
      new Refusal("an unknown remaining-accounts kind", d -> entry(d, 0).put("remaining_accounts", map("kind", "segment")), "unknown remaining-accounts kind segment"),
      new Refusal("a null entry", d -> d.put("instructions", list((Object) null)), "instructions[0]: must be an object"),
      new Refusal("a null source account", d -> entry(d, 0).put("source_accounts", list((Object) null)), "source_accounts[0]: must be an object"),
      new Refusal("omittable seats out of source order", d -> {
        source(d, 2).put("optional", "omitted");
        seat(d, 3).remove("sentinel");
        seat(d, 3).put("index", 4L);
        seat(d, 4).put("index", 3L);
      }, "forwards omittable position 2 after position 3"),
      // Beyond the ported rows: shapes a streaming parser meets field by field.
      new Refusal("a handler that is not an object", d -> entry(d, 0).put("handler", 5L), "must be an object"),
      new Refusal("a writable flag that is not a boolean", d -> source(d, 1).put("writable", 1L), "writable must be a boolean"),
      new Refusal("a seat index that is not an integer", d -> seat(d, 0).put("index", 1.5), "index must be a non-negative integer"),
      new Refusal("instructions that are not an array", d -> d.put("instructions", map()), "instructions must be an array")
  );

  private static Map<String, Object> provenance(final Map<String, Object> d) {
    return Json.object(d.get("provenance"));
  }

  private static Map<String, Object> handler(final Map<String, Object> d) {
    return Json.object(entry(d, 0).get("handler"));
  }

  /// Every field's own refusal, and the order the checks run in: each row triggers one
  /// message and no other, so a check that is skipped shows as another message.
  private static final List<Refusal> FIELD_REFUSALS = List.of(
      new Refusal("a schema version that is a string", d -> d.put("schema_version", "1"), "schema_version 1 is not 1"),
      new Refusal("a schema version that is an object", d -> d.put("schema_version", map()), "schema_version [object Object] is not 1"),
      new Refusal("a schema version that is an array", d -> d.put("schema_version", list(1L)), "schema_version 1 is not 1"),
      new Refusal("a schema version that is a non-empty object", d -> d.put("schema_version", map("x", 1L, "y", list(2L))), "schema_version [object Object] is not 1"),
      new Refusal("a disposition that is an object", d -> entry(d, 1).put("disposition", map("x", 1L)), "unknown disposition [object Object]"),
      new Refusal("a schema version that is a nested array", d -> d.put("schema_version", list(list(2L, null), "x")), "schema_version 2,,x is not 1"),
      new Refusal("a disposition that is an array", d -> entry(d, 1).put("disposition", list("map")), "unknown disposition map"),
      new Refusal("a remaining-accounts kind that is an array", d -> entry(d, 0).put("remaining_accounts", map("kind", list("any"))), "unknown remaining-accounts kind any"),
      new Refusal("an optional kind that is an array", d -> source(d, 2).put("optional", list("omitted")), "unknown optional kind omitted"),
      new Refusal("a seat kind that is an array", d -> seat(d, 1).put("kind", list("static")), "unknown seat kind static"),
      new Refusal("an address with a character outside the alphabet at its end", d -> d.put("program_id", "1".repeat(42) + "0"), "program_id is not an address"),
      new Refusal("an address with a character outside the alphabet at its start", d -> d.put("proxy_program_id", "O" + "1".repeat(42)), "proxy_program_id is not an address"),
      new Refusal("a schema version that is a boolean", d -> d.put("schema_version", true), "schema_version true is not 1"),
      new Refusal("an absent schema version", d -> d.remove("schema_version"), "schema_version undefined is not 1"),
      new Refusal("an environment that is not a string", d -> d.put("environment", 5L), "environment must be a non-blank string"),
      new Refusal("an absent environment", d -> d.remove("environment"), "environment must be a non-blank string"),
      new Refusal("an absent program id", d -> d.remove("program_id"), "program_id must be a non-blank string"),
      new Refusal("an absent proxy program id", d -> d.remove("proxy_program_id"), "proxy_program_id must be a non-blank string"),
      new Refusal("absent instructions", d -> d.remove("instructions"), "instructions must be an array"),
      new Refusal("a null entry after a valid one", d -> Json.array(d.get("instructions")).set(1, null), "instructions[1]: must be an object"),
      new Refusal("two unknown top-level fields", d -> {
        d.put("aaa", 1L);
        d.put("bbb", 2L);
      }, "unknown field \"aaa\""),
      new Refusal("a provenance that is not an object", d -> d.put("provenance", 5L), "must be an object"),
      new Refusal("a blank provenance generator", d -> provenance(d).put("generator", ""), "generator must be a non-blank string"),
      new Refusal("a provenance source idl that is not a string", d -> provenance(d).put("source_idl", 5L), "source_idl must be a non-blank string"),
      new Refusal("a provenance proxy idl that is not a string", d -> provenance(d).put("proxy_idl", true), "proxy_idl must be a non-blank string"),
      new Refusal("a config revision that is not a number", d -> provenance(d).put("config_revision", "1"), "config_revision must be a non-negative integer"),
      new Refusal("a negative config revision", d -> provenance(d).put("config_revision", -1L), "config_revision must be a non-negative integer"),
      new Refusal("two unknown provenance fields", d -> {
        provenance(d).put("aaa", 1L);
        provenance(d).put("bbb", 2L);
      }, "unknown field \"aaa\""),
      new Refusal("an entry that is not an object", d -> Json.array(d.get("instructions")).set(1, 5L), "must be an object"),
      new Refusal("an entry name that is not a string", d -> entry(d, 1).put("name", 5L), "name must be a non-blank string"),
      new Refusal("an entry without a name", d -> entry(d, 1).remove("name"), "name must be a non-blank string"),
      new Refusal("an entry without a discriminator", d -> entry(d, 2).remove("discriminator"), "discriminator must be an array"),
      new Refusal("a discriminator that is not an array", d -> entry(d, 2).put("discriminator", 5L), "discriminator must be an array"),
      new Refusal("a discriminator element that is not a number", d -> entry(d, 2).put("discriminator", list("x")), "discriminator[0] is not a byte"),
      new Refusal("a fractional discriminator byte", d -> entry(d, 2).put("discriminator", list(1.5)), "discriminator[0] is not a byte"),
      new Refusal("a second bad discriminator byte", d -> entry(d, 2).put("discriminator", list(7L, 256L)), "discriminator[1] is not a byte"),
      new Refusal("a bad discriminator byte ahead of others", d -> entry(d, 2).put("discriminator", list(256L, 1L, 2L)), "discriminator[0] is not a byte"),
      new Refusal("a disposition that is not a string", d -> entry(d, 1).put("disposition", 5L), "unknown disposition 5"),
      new Refusal("a passthrough carrying source accounts", d -> entry(d, 1).put("source_accounts", list()), "a passthrough entry carries no \"source_accounts\""),
      new Refusal("a passthrough carrying remaining accounts", d -> entry(d, 1).put("remaining_accounts", map("kind", "any")), "a passthrough entry carries no \"remaining_accounts\""),
      new Refusal("an unsupported entry without a reason", d -> entry(d, 2).remove("reason"), "reason must be a non-blank string"),
      new Refusal("a map entry without a handler", d -> entry(d, 0).remove("handler"), "handler: must be an object"),
      new Refusal("a map entry without source accounts", d -> entry(d, 0).remove("source_accounts"), "source_accounts must be an array"),
      new Refusal("source accounts that are not an array", d -> entry(d, 0).put("source_accounts", 5L), "source_accounts must be an array"),
      new Refusal("a map entry without destination accounts", d -> entry(d, 0).remove("destination_accounts"), "destination_accounts must be an array"),
      new Refusal("destination accounts that are not an array", d -> entry(d, 0).put("destination_accounts", map()), "destination_accounts must be an array"),
      new Refusal("two unknown entry fields", d -> {
        entry(d, 0).put("aaa", 1L);
        entry(d, 0).put("bbb", 2L);
      }, "unknown field \"aaa\""),
      new Refusal("remaining accounts that are not an object", d -> entry(d, 0).put("remaining_accounts", 5L), "remaining_accounts: must be an object"),
      new Refusal("remaining accounts with an unknown field", d -> entry(d, 0).put("remaining_accounts", map("kind", "any", "extra", 1L)), "unknown field \"extra\""),
      new Refusal("remaining accounts with two unknown fields", d -> entry(d, 0).put("remaining_accounts", map("aaa", 1L, "bbb", 2L)), "unknown field \"aaa\""),
      new Refusal("remaining accounts without a kind", d -> entry(d, 0).put("remaining_accounts", map()), "unknown remaining-accounts kind undefined"),
      new Refusal("a remaining-accounts kind that is not a string", d -> entry(d, 0).put("remaining_accounts", map("kind", 5L)), "unknown remaining-accounts kind 5"),
      new Refusal("a handler with an unknown field", d -> handler(d).put("x", 1L), "unknown field \"x\""),
      new Refusal("a handler with two unknown fields", d -> {
        handler(d).put("aaa", 1L);
        handler(d).put("bbb", 2L);
      }, "unknown field \"aaa\""),
      new Refusal("a handler discriminator that is not an array", d -> handler(d).put("discriminator", "x"), "discriminator must be an array"),
      new Refusal("a handler without a discriminator", d -> handler(d).remove("discriminator"), "discriminator must be an array"),
      new Refusal("a handler with a blank name", d -> handler(d).put("name", ""), "name must be a non-blank string"),
      new Refusal("a handler without a name", d -> handler(d).remove("name"), "name must be a non-blank string"),
      new Refusal("a source account that is not an object", d -> Json.array(entry(d, 0).get("source_accounts")).set(1, 5L), "must be an object"),
      new Refusal("a null source account after a valid one", d -> Json.array(entry(d, 0).get("source_accounts")).set(1, null), "source_accounts[1]: must be an object"),
      new Refusal("a source name that is not a string", d -> source(d, 1).put("name", 5L), "name must be a non-blank string"),
      new Refusal("a source without a name", d -> source(d, 1).remove("name"), "name must be a non-blank string"),
      new Refusal("a source without writable", d -> source(d, 1).remove("writable"), "writable must be a boolean"),
      new Refusal("a source signer that is not a boolean", d -> source(d, 1).put("signer", "yes"), "signer must be a boolean"),
      new Refusal("a source without signer", d -> source(d, 1).remove("signer"), "signer must be a boolean"),
      new Refusal("a dynamic_signer that is not a boolean", d -> source(d, 1).put("dynamic_signer", "yes"), "dynamic_signer must be true when present"),
      new Refusal("an optional kind that is not a string", d -> source(d, 2).put("optional", 5L), "unknown optional kind 5"),
      new Refusal("a blank expectation", d -> source(d, 0).put("expect", ""), "expect must be a non-blank string"),
      new Refusal("two unknown source fields", d -> {
        source(d, 0).put("aaa", 1L);
        source(d, 0).put("bbb", 2L);
      }, "unknown field \"aaa\""),
      new Refusal("a seat that is not an object", d -> Json.array(entry(d, 0).get("destination_accounts")).set(1, 5L), "must be an object"),
      new Refusal("a null seat after a valid one", d -> Json.array(entry(d, 0).get("destination_accounts")).set(4, null), "destination_accounts[4]: must be an object"),
      new Refusal("a seat with an unknown field", d -> seat(d, 0).put("x", 1L), "unknown field \"x\""),
      new Refusal("a seat with two unknown fields", d -> {
        seat(d, 0).put("aaa", 1L);
        seat(d, 0).put("bbb", 2L);
      }, "unknown field \"aaa\""),
      new Refusal("a seat without an index", d -> seat(d, 0).remove("index"), "index must be a non-negative integer"),
      new Refusal("a negative seat index", d -> seat(d, 0).put("index", -1L), "index must be a non-negative integer"),
      new Refusal("a seat index spelled as a decimal is its integer", d -> seat(d, 4).put("index", 100.0), "seats are not dense from 0: seat 100 of 5"),
      new Refusal("a fractional seat index", d -> seat(d, 0).put("index", 0.5), "index must be a non-negative integer"),
      new Refusal("a fractional schema version", d -> d.put("schema_version", 1.5), "schema_version 1.5 is not 1"),
      new Refusal("a schema version of two spelled as a decimal", d -> d.put("schema_version", 2.0), "schema_version 2 is not 1"),
      new Refusal("a seat index that is a string", d -> seat(d, 0).put("index", "0"), "index must be a non-negative integer"),
      new Refusal("a seat index equal to the seat count", d -> seat(d, 4).put("index", 5L), "seats are not dense from 0: seat 5 of 5"),
      new Refusal("a source position equal to the source count", d -> seat(d, 4).put("source", 4L), "seat 4 forwards source position 4, which is out of range of 4"),
      new Refusal("a seat writable that is not a boolean", d -> seat(d, 0).put("writable", 1L), "writable must be a boolean"),
      new Refusal("a seat without writable", d -> seat(d, 0).remove("writable"), "writable must be a boolean"),
      new Refusal("a seat signer that is not a boolean", d -> seat(d, 0).put("signer", "no"), "signer must be a boolean"),
      new Refusal("a seat without signer", d -> seat(d, 0).remove("signer"), "signer must be a boolean"),
      new Refusal("a seat kind that is not a string", d -> seat(d, 0).put("kind", 5L), "unknown seat kind 5"),
      new Refusal("a dynamic seat carrying an address", d -> seat(d, 0).put("address", PROXY), "a dynamic seat carries no \"address\""),
      new Refusal("a dynamic seat carrying a source", d -> seat(d, 0).put("source", 0L), "a dynamic seat carries no \"source\""),
      new Refusal("a dynamic seat with a blank name", d -> seat(d, 0).put("name", ""), "name must be a non-blank string"),
      new Refusal("a dynamic seat without a name", d -> seat(d, 0).remove("name"), "name must be a non-blank string"),
      new Refusal("a static seat carrying a name", d -> seat(d, 1).put("name", "glam_vault"), "a static seat carries no \"name\""),
      new Refusal("a static seat carrying a sentinel", d -> seat(d, 1).put("sentinel", true), "a static seat carries no \"sentinel\""),
      new Refusal("a static seat without an address", d -> seat(d, 1).remove("address"), "address must be a non-blank string"),
      new Refusal("a source seat carrying an address", d -> seat(d, 2).put("address", PROXY), "a source seat carries no \"address\""),
      new Refusal("a source seat whose source is not an integer", d -> seat(d, 2).put("source", "1"), "source must be a non-negative integer"),
      new Refusal("a source seat without a source", d -> seat(d, 2).remove("source"), "source must be a non-negative integer"),
      new Refusal("a sentinel that is not a boolean", d -> seat(d, 3).put("sentinel", "yes"), "sentinel must be true when present"),
      // the refusal does not depend on the order of the members
      new Refusal("instructions that are not an array ahead of the other fields", d -> {
        final var rest = new LinkedHashMap<>(d);
        rest.remove("instructions");
        d.clear();
        d.put("instructions", map());
        d.putAll(rest);
      }, "instructions must be an array"),
      new Refusal("an unknown field after instructions that are not an array", d -> {
        d.put("instructions", map());
        d.put("zzz", 1L);
      }, "unknown field \"zzz\""),
      new Refusal("a provenance that is not an object after instructions that are not an array", d -> {
        d.put("instructions", map());
        d.put("provenance", 5L);
      }, "provenance: must be an object"),
      // two faults: the checks run in the contract's order, whatever the text order
      new Refusal("a malformed address ahead of a wrong version", d -> {
        final var rest = new LinkedHashMap<>(d);
        d.clear();
        d.put("program_id", "short");
        rest.remove("program_id");
        d.putAll(rest);
        d.put("schema_version", 2L);
      }, "schema_version 2 is not 1"),
      new Refusal("a wrong version ahead of an unknown field", d -> {
        d.put("schema_version", 2L);
        d.put("zzz", 1L);
      }, "unknown field \"zzz\""),
      new Refusal("a malformed program id ahead of a malformed environment", d -> {
        final var rest = new LinkedHashMap<>(d);
        d.clear();
        d.put("program_id", "short");
        rest.remove("program_id");
        d.putAll(rest);
        d.put("environment", 5L);
      }, "environment must be a non-blank string"),
      // blank as ECMAScript's trim leaves it
      new Refusal("an environment of no-break spaces", d -> d.put("environment", "\u00A0\u00A0"), "environment must be a non-blank string"),
      new Refusal("an environment of a byte-order mark and a line separator", d -> d.put("environment", "\uFEFF\u2028"), "environment must be a non-blank string"),
      // an address is at most 44 characters, whatever the alphabet says
      new Refusal("an address of 45 alphabet characters", d -> d.put("program_id", "1".repeat(45)), "program_id is not an address"),
      new Refusal("an address of 31 alphabet characters", d -> d.put("program_id", "1".repeat(31)), "program_id is not an address"),
      // a revision is a long
      new Refusal("a config revision beyond the long range", d -> provenance(d).put("config_revision", 1e300), "config_revision must be a non-negative integer")
  );

  @TestFactory
  Stream<DynamicTest> refusesEachField() {
    return FIELD_REFUSALS.stream().map(refusal -> DynamicTest.dynamicTest(refusal.what(), () -> {
      final var document = valid();
      refusal.patch().accept(document);
      final var e = assertThrows(MappingDocumentException.class, () -> parse(document), refusal.what());
      assertTrue(e.getMessage().contains(refusal.message()),
          refusal.what() + ": expected a message containing \"" + refusal.message() + "\", got \"" + e.getMessage() + "\"");
    }));
  }

  /// JSON text patched where the tree cannot express it: a duplicate field, an exponent, or
  /// content after the document.
  private static MappingDocument parseText(final String json) {
    return MappingDocumentParser.parse(json, "mapping document");
  }

  private static String validText() {
    return Json.write(valid());
  }

  private record TextRefusal(String what, java.util.function.UnaryOperator<String> patch, String message) {
  }

  /// A field named twice is refused at every level, whether the second value is well-formed
  /// or not.
  private static final List<TextRefusal> TEXT_REFUSALS = List.of(
      new TextRefusal("a duplicate top-level field", t -> t.replace("\"environment\":\"test\"", "\"environment\":\"test\",\"environment\":\"x\""), "duplicate field \"environment\""),
      new TextRefusal("a duplicate provenance field", t -> t.replace("\"generator\":\"g\"", "\"generator\":\"g\",\"generator\":\"h\""), "duplicate field \"generator\""),
      new TextRefusal("a duplicate entry field", t -> t.replace("\"name\":\"read\"", "\"name\":\"read\",\"name\":\"read\""), "duplicate field \"name\""),
      new TextRefusal("a malformed second source_accounts after a valid one", t -> t.replace(",\"destination_accounts\":", ",\"source_accounts\":5,\"destination_accounts\":"), "duplicate field \"source_accounts\""),
      new TextRefusal("a duplicate handler field", t -> t.replace("\"name\":\"proxy_do\"", "\"name\":\"proxy_do\",\"name\":\"proxy_do\""), "duplicate field \"name\""),
      new TextRefusal("a duplicate source account field", t -> t.replace("\"name\":\"thing\"", "\"name\":\"thing\",\"writable\":true"), "duplicate field \"writable\""),
      new TextRefusal("a duplicate seat field", t -> t.replace("\"index\":1,\"kind\":\"static\"", "\"index\":1,\"index\":1,\"kind\":\"static\""), "duplicate field \"index\""),
      new TextRefusal("a duplicate remaining-accounts field", t -> t.replace("{\"kind\":\"any\"}", "{\"kind\":\"any\",\"kind\":\"any\"}"), "duplicate field \"kind\""),
      // two fields named twice: the first duplicate met is the one named, at every level
      new TextRefusal("two duplicate top-level fields", t -> t.replace("\"environment\":\"test\",\"program_id\"", "\"environment\":\"test\",\"environment\":\"test\",\"program_id\"").replace("\"proxy_program_id\":", "\"schema_version\":1,\"proxy_program_id\":"), "duplicate field \"environment\""),
      new TextRefusal("two duplicate provenance fields", t -> t.replace("\"generator\":\"g\",\"config_revision\":1", "\"generator\":\"g\",\"generator\":\"g\",\"config_revision\":1,\"config_revision\":1"), "duplicate field \"generator\""),
      new TextRefusal("two duplicate entry fields", t -> t.replace("\"name\":\"read\",\"discriminator\":[3]", "\"name\":\"read\",\"name\":\"read\",\"discriminator\":[3],\"discriminator\":[3]"), "duplicate field \"name\""),
      new TextRefusal("two duplicate handler fields", t -> t.replace("\"name\":\"proxy_do\",\"discriminator\":[9]", "\"name\":\"proxy_do\",\"name\":\"proxy_do\",\"discriminator\":[9],\"discriminator\":[9]"), "duplicate field \"name\""),
      new TextRefusal("two duplicate source account fields", t -> t.replace("\"name\":\"thing\",\"writable\":true", "\"name\":\"thing\",\"name\":\"thing\",\"writable\":true,\"writable\":true"), "duplicate field \"name\""),
      new TextRefusal("two duplicate seat fields", t -> t.replace("\"index\":1,\"kind\":\"static\"", "\"index\":1,\"index\":1,\"kind\":\"static\",\"kind\":\"static\""), "duplicate field \"index\""),
      new TextRefusal("two duplicate remaining-accounts fields", t -> t.replace("{\"kind\":\"any\"}", "{\"kind\":\"any\",\"kind\":\"any\",\"extra\":1,\"extra\":1}"), "duplicate field \"kind\""),
      new TextRefusal("content after the document", t -> t + " x", "content after the document"),
      new TextRefusal("a number token with an empty exponent", t -> t.replace("\"schema_version\":1,", "\"schema_version\":1e,"), "is not JSON"),
      new TextRefusal("a number with a leading zero", t -> t.replace("\"schema_version\":1,", "\"schema_version\":01,"), "is not JSON"),
      new TextRefusal("a number with a trailing point", t -> t.replace("\"schema_version\":1,", "\"schema_version\":1.,"), "is not JSON"),
      new TextRefusal("a plus-signed number", t -> t.replace("\"schema_version\":1,", "\"schema_version\":+1,"), "is not JSON"),
      new TextRefusal("a bare fraction", t -> t.replace("\"schema_version\":1,", "\"schema_version\":.5,"), "is not JSON"),
      new TextRefusal("a NaN", t -> t.replace("\"schema_version\":1,", "\"schema_version\":NaN,"), "is not JSON"),
      new TextRefusal("a hexadecimal number", t -> t.replace("\"schema_version\":1,", "\"schema_version\":0x10,"), "is not JSON"),
      new TextRefusal("a discriminator byte with a leading zero", t -> t.replace("\"discriminator\":[4]", "\"discriminator\":[04]"), "is not JSON"),
      new TextRefusal("a brace as a separator", t -> t.replace("\"schema_version\":1,", "\"schema_version\":1{"), "is not JSON"),
      new TextRefusal("an array closed by null", t -> t.replace("\"discriminator\":[4]", "\"discriminator\":[4 null"), "is not JSON"),
      new TextRefusal("a document closed by null", t -> t.substring(0, t.length() - 1) + " null", "is not JSON"),
      new TextRefusal("a raw tab in a string", t -> t.replace("\"environment\":\"test\"", "\"environment\":\"te\tst\""), "is not JSON"),
      new TextRefusal("a raw control character in a string", t -> t.replace("\"environment\":\"test\"", "\"environment\":\"te\u0000st\""), "is not JSON"),
      new TextRefusal("an invalid escape in a string", t -> t.replace("\"environment\":\"test\"", "\"environment\":\"te\\xst\""), "is not JSON"),
      new TextRefusal("a truncated document", t -> t.substring(0, 40), "is not JSON"),
      new TextRefusal("an empty document", t -> "", "is not JSON"),
      new TextRefusal("a document of whitespace", t -> "  \n", "is not JSON"),
      new TextRefusal("a document that is a string", t -> "\"x\"", "must be an object"),
      new TextRefusal("nesting deeper than the bound", t -> t.replace("\"provenance\":", "\"provenance\":" + "[".repeat(70) + "]".repeat(70) + ",\"generator2\":"), "is not JSON: nesting deeper than 64"),
      new TextRefusal("a second document after the first", t -> t + "{}", "content after the document"),
      new TextRefusal("a large schema version uses JavaScript exponent formatting", t -> t.replace("\"schema_version\":1,", "\"schema_version\":1e21,"), "schema_version 1e+21 is not 1"),
      new TextRefusal("a small schema version uses JavaScript exponent formatting", t -> t.replace("\"schema_version\":1,", "\"schema_version\":1e-7,"), "schema_version 1e-7 is not 1"),
      new TextRefusal("a discriminator byte in exponent notation out of range", t -> t.replace("\"discriminator\":[4]", "\"discriminator\":[3e2]"), "discriminator[0] is not a byte")
  );

  @TestFactory
  Stream<DynamicTest> refusesPatchedText() {
    return TEXT_REFUSALS.stream().map(refusal -> DynamicTest.dynamicTest(refusal.what(), () -> {
      final var text = refusal.patch().apply(validText());
      assertNotEquals(validText(), text, refusal.what() + ": the patch did not apply");
      final var e = assertThrows(MappingDocumentException.class, () -> parseText(text), refusal.what());
      assertTrue(e.getMessage().contains(refusal.message()),
          refusal.what() + ": expected a message containing \"" + refusal.message() + "\", got \"" + e.getMessage() + "\"");
    }));
  }

  /// Numbers are read as JavaScript reads them: a spelling with a fraction part of zero or an
  /// exponent is the integer it denotes.
  @Test
  void admitsIntegersHoweverTheyAreSpelled() {
    final var text = validText()
        .replace("\"schema_version\":1,", "\"schema_version\":1.0,")
        .replace("\"discriminator\":[4]", "\"discriminator\":[4e0]")
        .replace("\"discriminator\":[3]", "\"discriminator\":[3.0]")
        .replace("\"config_revision\":1", "\"config_revision\":1e0");
    assertNotEquals(validText(), text);
    final var document = parseText(text);
    assertEquals(1, document.schemaVersion());
    assertEquals(1, document.provenance().configRevision());
    assertArrayEquals(new byte[]{3}, document.instructions().get(1).discriminator().data());
    assertArrayEquals(new byte[]{4}, document.instructions().get(2).discriminator().data());
    assertEquals(1, parseText(validText().replace("\"schema_version\":1,", "\"schema_version\":1e0,")).schemaVersion());
    assertEquals(1, parseText(validText() + "  \n").schemaVersion(), "whitespace after the document is not content");
  }

  /// A numeric token is a binary64 value before the schema compares it.
  @Test
  void admitsASchemaVersionRoundedToOneByJavaScript() {
    final var text = validText().replace("\"schema_version\":1,", "\"schema_version\":1.0000000000000001,");
    assertNotEquals(validText(), text);
    assertEquals(1, parseText(text).schemaVersion());
  }

  @Test
  void admitsADiscriminatorByteRoundedToAnIntegerByJavaScript() {
    final var text = validText().replace("\"discriminator\":[4]", "\"discriminator\":[4.0000000000000001]");
    assertNotEquals(validText(), text);
    assertArrayEquals(new byte[]{4}, parseText(text).instructions().get(2).discriminator().data());
  }

  /// The value is Infinity; this modest exponent exposes expansion without risking the heap.
  @Test
  void rejectsAnOverflowingSchemaVersionWithABoundedDiagnostic() {
    final var text = validText().replace("\"schema_version\":1,", "\"schema_version\":1e1000,");
    assertNotEquals(validText(), text);
    final var e = assertThrows(MappingDocumentException.class, () -> parseText(text));
    assertEquals("mapping document: schema_version Infinity is not 1", e.getMessage());
  }

  /// `String(number)` in JavaScript, over the JSON spellings a document can carry: plain digits
  /// up to 1e21, a fraction down to 1e-6, exponent form beyond, and Infinity past binary64.
  @Test
  void plainNumberPrintsAsJavaScriptDoes() {
    final var expected = new java.util.LinkedHashMap<String, String>();
    expected.put("0", "0");
    expected.put("-0", "0");
    expected.put("0.0", "0");
    expected.put("1", "1");
    expected.put("1.0", "1");
    expected.put("2.50", "2.5");
    expected.put("100", "100");
    expected.put("1e2", "100");
    expected.put("1E2", "100");
    expected.put("1234.5678", "1234.5678");
    expected.put("0.5", "0.5");
    expected.put("1e20", "100000000000000000000");
    expected.put("1e21", "1e+21");
    expected.put("1.5e21", "1.5e+21");
    expected.put("123456789012345000000000", "1.23456789012345e+23");
    expected.put("0.000001", "0.000001");
    expected.put("1e-6", "0.000001");
    expected.put("0.000001234", "0.000001234");
    expected.put("1e-7", "1e-7");
    expected.put("1.5e-7", "1.5e-7");
    expected.put("2.5e-8", "2.5e-8");
    expected.put("-1.5", "-1.5");
    expected.put("-1e21", "-1e+21");
    expected.put("-1e-7", "-1e-7");
    expected.put("1e1000", "Infinity");
    expected.put("-1e1000", "-Infinity");
    expected.put("1.0000000000000001", "1");
    expected.put("2147483648", "2147483648");
    for (final var entry : expected.entrySet()) {
      assertEquals(entry.getValue(), MappingDocumentParser.plainNumber(entry.getKey()), entry.getKey());
    }
  }

  /// `Number.isInteger` over binary64, held to the int range the document's positions use.
  @Test
  void integerValueReadsAsJavaScriptDoes() {
    final var expected = new java.util.LinkedHashMap<String, Integer>();
    expected.put("0", 0);
    expected.put("-0", 0);
    expected.put("1", 1);
    expected.put("1.0", 1);
    expected.put("1e2", 100);
    expected.put("1.0000000000000001", 1);
    expected.put("4.0000000000000001", 4);
    expected.put("-1", -1);
    expected.put("2147483647", Integer.MAX_VALUE);
    expected.put("-2147483648", Integer.MIN_VALUE);
    expected.put("1.5", null);
    expected.put("0.5", null);
    expected.put("-0.5", null);
    expected.put("2147483648", null);
    expected.put("-2147483649", null);
    expected.put("1e21", null);
    expected.put("1e1000", null);
    expected.put("-1e1000", null);
    for (final var entry : expected.entrySet()) {
      assertEquals(entry.getValue(), MappingDocumentParser.integerValue(entry.getKey()), entry.getKey());
    }
  }

  /// The bytes 0 and 255 are bytes; a discriminator of them is admitted as it is.
  @Test
  void admitsTheEdgeBytesOfADiscriminator() {
    final var document = valid();
    entry(document, 2).put("discriminator", list(0L, 255L));
    final var parsed = parse(document);
    assertArrayEquals(new byte[]{0, (byte) 255}, parsed.instructions().get(2).discriminator().data());
  }

  /// A provenance carries what it says: a config revision other than the default, and each
  /// string field.
  @Test
  void admitsAFullProvenance() {
    final var document = valid();
    document.put("provenance", map("generator", "g", "source_idl", "s", "proxy_idl", "p", "config_revision", 7L));
    assertEquals(new Provenance("g", "s", "p", 7), parse(document).provenance());
  }

  /// An expectation that is an address pins the position to that address.
  @Test
  void admitsAnAddressExpectation() {
    final var document = valid();
    source(document, 1).put("expect", PROXY);
    final var mapped = (InstructionEntry.Mapped) parse(document).instructions().getFirst();
    assertEquals(new Expectation.Address(PublicKey.fromBase58Encoded(PROXY)), mapped.sourceAccounts().get(1).expect());
    assertEquals(PROXY, mapped.sourceAccounts().get(1).expect().jsonValue());
  }

  /// A document may list its entries in any order; the discriminator shadow check names the
  /// shorter one whichever comes first.
  @Test
  void theShadowCheckNamesTheShorterDiscriminatorWhicheverComesFirst() {
    final var document = valid();
    entry(document, 0).put("discriminator", list(3L, 9L));
    final var e = assertThrows(MappingDocumentException.class, () -> parse(document));
    assertTrue(e.getMessage().contains("discriminator of read ([3]) is a prefix of do's"), e.getMessage());
  }

  /// A string of characters `trim()` keeps is not blank, and a revision past the int range is
  /// a revision.
  @Test
  void admitsWhatTrimKeepsAndALargeRevision() {
    final var document = valid();
    document.put("environment", "\u001C");
    provenance(document).put("config_revision", 2147483648L);
    final var parsed = parse(document);
    assertEquals("\u001C", parsed.environment());
    assertEquals(2147483648L, parsed.provenance().configRevision());
  }

  /// The shortest and the longest spellings of a 32-byte key are addresses.
  @Test
  void admitsAddressesOfThirtyTwoAndFortyFourCharacters() {
    final var shortest = "1".repeat(32);
    final var longest = PublicKey.createPubKey(new byte[]{
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1}).toBase58();
    assertEquals(44, longest.length());
    final var document = valid();
    document.put("program_id", shortest);
    document.put("proxy_program_id", longest);
    final var parsed = parse(document);
    assertEquals(shortest, parsed.programId().toBase58());
    assertEquals(longest, parsed.proxyProgramId().toBase58());
  }

  /// Blank as ECMAScript's `trim()` leaves it: each WhiteSpace and LineTerminator character,
  /// and no other.
  @Test
  void isBlankFollowsTrim() {
    for (final char c : new char[]{'\t', 0x0B, '\f', ' ', 0xA0, 0xFEFF, '\n', '\r', 0x2028, 0x2029, 0x3000, 0x1680, 0x2007, 0x202F}) {
      assertTrue(MappingDocumentParser.isBlank(String.valueOf(c)), Integer.toHexString(c));
      assertTrue(MappingDocumentParser.isBlank(" " + c + " "), Integer.toHexString(c));
    }
    assertTrue(MappingDocumentParser.isBlank(""));
    for (final char c : new char[]{'a', '_', 0x1C, 0x1F, 0x85, 0x180E, 0x200B}) {
      assertFalse(MappingDocumentParser.isBlank(String.valueOf(c)), Integer.toHexString(c));
      assertFalse(MappingDocumentParser.isBlank(" " + c + " "), Integer.toHexString(c));
    }
  }

  /// A revision is any integral binary64 value in long range.
  @Test
  void longValueReadsAsJavaScriptDoesInLongRange() {
    assertEquals(0L, MappingDocumentParser.longValue("0"));
    assertEquals(-1L, MappingDocumentParser.longValue("-1"));
    assertEquals(1_000_000_000_000_000_000L, MappingDocumentParser.longValue("1e18"));
    assertEquals(9_007_199_254_740_992L, MappingDocumentParser.longValue("9007199254740993"));
    assertEquals(Long.MIN_VALUE, MappingDocumentParser.longValue("-9223372036854775808"));
    assertNull(MappingDocumentParser.longValue("9223372036854775807"), "2^63 as a double is past the long range");
    assertEquals(Long.MIN_VALUE, MappingDocumentParser.longValue("-9223372036854775809"), "rounds to -2^63, which is a long");
    assertEquals(-9223372036854774784L, MappingDocumentParser.longValue("-9223372036854774784"), "the largest double below -2^63 that is a long");
    assertNull(MappingDocumentParser.longValue("1.5"));
    assertNull(MappingDocumentParser.longValue("1e19"));
    assertNull(MappingDocumentParser.longValue("-1e19"));
    assertNull(MappingDocumentParser.longValue("1e1000"));
  }

  /// A provenance without a config revision carries revision 1.
  @Test
  void aProvenanceWithoutAConfigRevisionCarriesTheDefault() {
    final var document = valid();
    document.put("provenance", map("generator", "g"));
    assertEquals(new Provenance("g", null, null, 1), parse(document).provenance());
  }

  /// The enum names round-trip through their JSON spellings.
  @Test
  void jsonNamesRoundTrip() {
    for (final var kind : OptionalKind.values()) {
      assertSame(kind, OptionalKind.fromJsonName(kind.jsonName()));
    }
    assertEquals("omitted", OptionalKind.OMITTED.jsonName());
    assertEquals("program_id", OptionalKind.PROGRAM_ID.jsonName());
    assertNull(OptionalKind.fromJsonName("maybe"));
    for (final var rule : RemainingAccounts.values()) {
      assertSame(rule, RemainingAccounts.fromJsonName(rule.jsonName()));
    }
    assertEquals("any", RemainingAccounts.ANY.jsonName());
    assertEquals("none", RemainingAccounts.NONE.jsonName());
    assertNull(RemainingAccounts.fromJsonName("segment"));
    for (final var name : DynamicAccountName.values()) {
      assertSame(name, DynamicAccountName.fromJsonName(name.jsonName()));
    }
    for (final var reason : UnsupportedReason.values()) {
      assertFalse(reason.jsonName().isBlank());
    }
  }

  @Test
  void readsDocumentsFromFiles(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws java.io.IOException {
    final var file = dir.resolve(PROGRAM + ".json");
    java.nio.file.Files.writeString(file, Json.write(valid()));
    // not documents: another extension, and a directory whose name ends in .json
    java.nio.file.Files.writeString(dir.resolve("README.txt"), "not a document");
    java.nio.file.Files.createDirectory(dir.resolve("nested.json"));
    java.nio.file.Files.writeString(dir.resolve("nested.json").resolve("inner.json"), Json.write(valid()));
    // a second document, named to sort first: the directory reads in file-name order
    final var second = valid();
    second.put("program_id", PROXY);
    java.nio.file.Files.writeString(dir.resolve("0-" + PROXY + ".json"), Json.write(second));
    assertEquals(PublicKey.fromBase58Encoded(PROGRAM), MappingDocuments.read(file).programId());
    final var documents = MappingDocuments.readDirectory(dir);
    assertEquals(2, documents.size());
    assertEquals(PublicKey.fromBase58Encoded(PROXY), documents.get(0).programId());
    assertEquals(PublicKey.fromBase58Encoded(PROGRAM), documents.get(1).programId());
    assertThrows(IllegalArgumentException.class, () -> MappingDocuments.readDirectory(file));
    // a file that does not admit is refused under its file name, whether it is JSON or not
    final var broken = dir.resolve("broken.json");
    java.nio.file.Files.writeString(broken, "[]");
    final var e = assertThrows(MappingDocumentException.class, () -> MappingDocuments.readDirectory(dir));
    assertEquals("broken.json", e.at());
    java.nio.file.Files.writeString(broken, Json.write(valid()).substring(0, 600));
    final var truncated = assertThrows(MappingDocumentException.class, () -> MappingDocuments.readDirectory(dir));
    assertEquals("broken.json", truncated.at());
    assertTrue(truncated.getMessage().contains("is not JSON"), truncated.getMessage());
  }

  @TestFactory
  Stream<DynamicTest> refuses() {
    return REFUSALS.stream().map(refusal -> DynamicTest.dynamicTest(refusal.what(), () -> {
      final var document = valid();
      refusal.patch().accept(document);
      final var e = assertThrows(MappingDocumentException.class, () -> parse(document), refusal.what());
      assertTrue(e.getMessage().contains(refusal.message()),
          refusal.what() + ": expected a message containing \"" + refusal.message() + "\", got \"" + e.getMessage() + "\"");
    }));
  }

  /// The 2026-09-24 fuzz finding: a lead byte with no continuation inside the environment
  /// string. The syntax pass refuses it as not JSON where the reader threw its own exception;
  /// the bytes are a committed seed of the mappingConfig corpus.
  @Test
  void aMalformedUtf8ByteInAStringIsNotJson() throws java.io.IOException {
    final var seed = java.nio.file.Path.of("src/test/resources/fuzz/mappingConfig/malformed-utf8-environment");
    final byte[] bytes = java.nio.file.Files.readAllBytes(seed);
    assertEquals((byte) 0xC8, bytes[36]);
    final var e = assertThrows(MappingDocumentException.class, () -> MappingDocumentParser.parse(bytes, "seed"));
    assertEquals("seed: is not JSON: malformed UTF-8 at offset 36", e.getMessage());
    final var read = assertThrows(MappingDocumentException.class, () -> MappingDocuments.read(seed));
    assertEquals("malformed-utf8-environment", read.at());
    assertEquals("is not JSON: malformed UTF-8 at offset 36", read.detail());
  }

  /// The echo of an array prints a null element as nothing and the string "null" as itself.
  @Test
  void anArrayEchoTellsANullElementFromTheStringNull() {
    final var head = "{\"environment\":\"test\",\"program_id\":\"" + PROGRAM + "\",\"proxy_program_id\":\"" + PROXY
        + "\",\"instructions\":[],\"schema_version\":";
    assertEquals(": schema_version null is not 1", assertThrows(MappingDocumentException.class,
        () -> MappingDocumentParser.parse(head + "[\"null\"]}", "")).getMessage());
    assertEquals(": schema_version ,2 is not 1", assertThrows(MappingDocumentException.class,
        () -> MappingDocumentParser.parse(head + "[null,2]}", "")).getMessage());
    assertEquals(": schema_version ,,x is not 1", assertThrows(MappingDocumentException.class,
        () -> MappingDocumentParser.parse(head + "[null,null,\"x\"]}", "")).getMessage());
  }

  /// The spelling bound and the alphabet check keep the decoder from a string outside them:
  /// the decoder's cost grows with the square of the length, so a long string must never
  /// reach it, and a foreign one is refused before it too; a string inside them reaches
  /// it once.
  @Test
  void theBoundAndTheAlphabetKeepStringsOutsideThemFromTheDecoder() {
    final var calls = new java.util.concurrent.atomic.AtomicInteger();
    final java.util.function.Function<String, PublicKey> counting = value -> {
      calls.incrementAndGet();
      return PublicKey.fromBase58Encoded(value);
    };
    for (final var text : List.of("1".repeat(31), "z".repeat(45), "z".repeat(100_000), "1".repeat(31) + "0",
        "O" + "1".repeat(31), "1".repeat(43) + "l", "I".repeat(44), "")) {
      assertNull(MappingDocumentParser.decodeAddress(text, counting), text.length() + " characters");
    }
    assertEquals(0, calls.get(), "the decoder never sees a string the bound or the alphabet check refuses");
    assertNull(MappingDocumentParser.decodeAddress("z".repeat(44), counting), "44 z's decode to 33 bytes");
    assertEquals(1, calls.get());
    assertEquals(PROGRAM, MappingDocumentParser.decodeAddress(PROGRAM, counting).toBase58());
    assertEquals(2, calls.get());
  }

  @Test
  void aRefusalNamesWhereItIs() {
    final var document = valid();
    seat(document, 4).put("index", 7L);
    final var e = assertThrows(MappingDocumentException.class, () -> parse(document));
    assertEquals("mapping document " + PROGRAM + " instructions[0]", e.at());
  }
}
