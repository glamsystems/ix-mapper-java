package systems.glam.ix.proxy;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;
import software.sava.core.accounts.PublicKey;
import software.sava.core.programs.Discriminator;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/// A document built from records takes the field checks a parsed one took, on construction:
/// no field null, no index negative, no name or reason blank, no null in a list; and a record
/// holds its own copy of a list it was given.
final class RecordsTests {

  private static final PublicKey PROGRAM = PublicKey.fromBase58Encoded("Src1111111111111111111111111111111111111111");
  private static final PublicKey PROXY = PublicKey.fromBase58Encoded("Proxy11111111111111111111111111111111111111");
  private static final Discriminator DISC = Discriminator.createDiscriminator(new byte[]{1});
  private static final Discriminator EMPTY = Discriminator.createDiscriminator(new byte[0]);
  private static final Handler HANDLER = new Handler("h", Discriminator.createDiscriminator(new byte[]{9}));
  private static final SourceAccount SOURCE = new SourceAccount("thing", true, false, false, null, null);
  private static final DestinationAccount SEAT = new DestinationAccount.Source(0, 0, true, false, false);
  private static final software.sava.core.tx.Instruction INSTRUCTION =
      software.sava.core.tx.Instruction.createInstruction(PROGRAM, List.of(), new byte[]{1});

  private static <T> List<T> withNull() {
    final var list = new ArrayList<T>();
    list.add(null);
    return list;
  }

  private record Refusal(String what, Executable construct, String at, String detail) {
  }

  private static final List<Refusal> REFUSALS = List.of(
      new Refusal("schema_version 2", () -> new MappingDocument(2, "test", PROGRAM, PROXY, null, List.of()), "MappingDocument", "schema_version is 2, not 1"),
      new Refusal("schema_version 0", () -> new MappingDocument(0, "test", PROGRAM, PROXY, null, List.of()), "MappingDocument", "schema_version is 0, not 1"),
      new Refusal("a null environment", () -> new MappingDocument(1, null, PROGRAM, PROXY, null, List.of()), "MappingDocument", "environment is missing or blank"),
      new Refusal("a blank environment", () -> new MappingDocument(1, " \t", PROGRAM, PROXY, null, List.of()), "MappingDocument", "environment is missing or blank"),
      new Refusal("a null program_id", () -> new MappingDocument(1, "test", null, PROXY, null, List.of()), "MappingDocument", "program_id is missing"),
      new Refusal("a null proxy_program_id", () -> new MappingDocument(1, "test", PROGRAM, null, null, List.of()), "MappingDocument", "proxy_program_id is missing"),
      new Refusal("null instructions", () -> new MappingDocument(1, "test", PROGRAM, PROXY, null, null), "MappingDocument", "instructions is missing"),
      new Refusal("a null entry", () -> new MappingDocument(1, "test", PROGRAM, PROXY, null, withNull()), "MappingDocument", "instructions holds a null element"),

      new Refusal("a mapped entry without a name", () -> new InstructionEntry.Mapped(null, DISC, HANDLER, List.of(), List.of(), RemainingAccounts.ANY), "InstructionEntry.Mapped", "name is missing or blank"),
      new Refusal("a mapped entry with a blank name", () -> new InstructionEntry.Mapped("\u00a0", DISC, HANDLER, List.of(), List.of(), RemainingAccounts.ANY), "InstructionEntry.Mapped", "name is missing or blank"),
      new Refusal("a mapped entry without a discriminator", () -> new InstructionEntry.Mapped("m", null, HANDLER, List.of(), List.of(), RemainingAccounts.ANY), "InstructionEntry.Mapped", "m lacks a discriminator"),
      new Refusal("a mapped entry with an empty discriminator", () -> new InstructionEntry.Mapped("m", EMPTY, HANDLER, List.of(), List.of(), RemainingAccounts.ANY), "InstructionEntry.Mapped", "m lacks a discriminator"),
      new Refusal("a mapped entry without a handler", () -> new InstructionEntry.Mapped("m", DISC, null, List.of(), List.of(), RemainingAccounts.ANY), "InstructionEntry.Mapped", "handler is missing"),
      new Refusal("null source accounts", () -> new InstructionEntry.Mapped("m", DISC, HANDLER, null, List.of(), RemainingAccounts.ANY), "InstructionEntry.Mapped", "source_accounts is missing"),
      new Refusal("a null source account", () -> new InstructionEntry.Mapped("m", DISC, HANDLER, withNull(), List.of(), RemainingAccounts.ANY), "InstructionEntry.Mapped", "source_accounts holds a null element"),
      new Refusal("null destination accounts", () -> new InstructionEntry.Mapped("m", DISC, HANDLER, List.of(), null, RemainingAccounts.ANY), "InstructionEntry.Mapped", "destination_accounts is missing"),
      new Refusal("a null destination account", () -> new InstructionEntry.Mapped("m", DISC, HANDLER, List.of(), withNull(), RemainingAccounts.ANY), "InstructionEntry.Mapped", "destination_accounts holds a null element"),
      new Refusal("null supplied accounts", () -> new InstructionEntry.Mapped("m", DISC, HANDLER, List.of(), List.of(), RemainingAccounts.ANY, null), "InstructionEntry.Mapped", "supplied_accounts is missing"),
      new Refusal("a null supplied account", () -> new InstructionEntry.Mapped("m", DISC, HANDLER, List.of(), List.of(), RemainingAccounts.ANY, withNull()), "InstructionEntry.Mapped", "supplied_accounts holds a null element"),
      new Refusal("a supplied account without a role", () -> new SuppliedAccount(null, List.of(), false), "SuppliedAccount", "role is missing or blank"),
      new Refusal("a supplied account with a blank role", () -> new SuppliedAccount(" ", List.of(), false), "SuppliedAccount", "role is missing or blank"),
      new Refusal("a supplied account with null positions", () -> new SuppliedAccount("r", null, false), "SuppliedAccount", "of is missing"),
      new Refusal("a supplied account with a null position", () -> new SuppliedAccount("r", withNull(), false), "SuppliedAccount", "of holds a null element"),
      new Refusal("a supplied account with a negative position", () -> new SuppliedAccount("r", List.of(-1), false), "SuppliedAccount", "of is negative"),
      new Refusal("no remaining-accounts rule", () -> new InstructionEntry.Mapped("m", DISC, HANDLER, List.of(), List.of(), null), "InstructionEntry.Mapped", "remaining_accounts is missing"),

      new Refusal("a passthrough without a name", () -> new InstructionEntry.Passthrough(null, DISC, "r"), "InstructionEntry.Passthrough", "name is missing or blank"),
      new Refusal("a passthrough without a discriminator", () -> new InstructionEntry.Passthrough("p", null, "r"), "InstructionEntry.Passthrough", "p lacks a discriminator"),
      new Refusal("a passthrough with an empty discriminator", () -> new InstructionEntry.Passthrough("p", EMPTY, "r"), "InstructionEntry.Passthrough", "p lacks a discriminator"),
      new Refusal("a passthrough without a reason", () -> new InstructionEntry.Passthrough("p", DISC, null), "InstructionEntry.Passthrough", "reason is missing or blank"),
      new Refusal("a passthrough with a blank reason", () -> new InstructionEntry.Passthrough("p", DISC, " "), "InstructionEntry.Passthrough", "reason is missing or blank"),
      new Refusal("an unsupported entry without a name", () -> new InstructionEntry.Unsupported("", DISC, "r"), "InstructionEntry.Unsupported", "name is missing or blank"),
      new Refusal("an unsupported entry without a discriminator", () -> new InstructionEntry.Unsupported("u", null, "r"), "InstructionEntry.Unsupported", "u lacks a discriminator"),
      new Refusal("an unsupported entry with an empty discriminator", () -> new InstructionEntry.Unsupported("u", EMPTY, "r"), "InstructionEntry.Unsupported", "u lacks a discriminator"),
      new Refusal("an unsupported entry without a reason", () -> new InstructionEntry.Unsupported("u", DISC, ""), "InstructionEntry.Unsupported", "reason is missing or blank"),

      new Refusal("a handler without a name", () -> new Handler(null, DISC), "Handler", "name is missing or blank"),
      new Refusal("a handler with a blank name", () -> new Handler("\n", DISC), "Handler", "name is missing or blank"),
      new Refusal("a handler without a discriminator", () -> new Handler("h", null), "Handler", "the handler lacks a discriminator"),
      new Refusal("a handler with an empty discriminator", () -> new Handler("h", EMPTY), "Handler", "the handler lacks a discriminator"),
      new Refusal("a source account without a name", () -> new SourceAccount(null, false, false, false, null, null), "SourceAccount", "name is missing or blank"),
      new Refusal("a source account with a blank name", () -> new SourceAccount("", false, false, false, null, null), "SourceAccount", "name is missing or blank"),

      new Refusal("a dynamic seat at a negative index", () -> new DestinationAccount.Dynamic(-1, DynamicAccountName.GLAM_STATE, false, false), "DestinationAccount.Dynamic", "index is negative"),
      new Refusal("a dynamic seat without a name", () -> new DestinationAccount.Dynamic(0, null, false, false), "DestinationAccount.Dynamic", "name is missing"),
      new Refusal("a static seat at a negative index", () -> new DestinationAccount.Static(-1, PROXY, false, false), "DestinationAccount.Static", "index is negative"),
      new Refusal("a static seat without an address", () -> new DestinationAccount.Static(0, null, false, false), "DestinationAccount.Static", "address is missing"),
      new Refusal("a source seat at a negative index", () -> new DestinationAccount.Source(-1, 0, false, false, false), "DestinationAccount.Source", "index is negative"),
      new Refusal("a source seat from a negative position", () -> new DestinationAccount.Source(0, -1, false, false, false), "DestinationAccount.Source", "source is negative"),
      new Refusal("a dynamic expectation without a name", () -> new Expectation.Dynamic(null), "Expectation.Dynamic", "name is missing"),
      new Refusal("an address expectation without an address", () -> new Expectation.Address(null), "Expectation.Address", "address is missing"),
      new Refusal("a blank generator", () -> new Provenance(" ", null, null, 1), "Provenance", "generator is blank"),
      new Refusal("a blank source_idl", () -> new Provenance("g", "", null, 1), "Provenance", "source_idl is blank"),
      new Refusal("a blank proxy_idl", () -> new Provenance("g", null, "\t", 1), "Provenance", "proxy_idl is blank"),
      new Refusal("config_revision 0", () -> new Provenance("g", null, null, 0), "Provenance", "config_revision is not positive"),
      new Refusal("config_revision -1", () -> new Provenance("g", null, null, -1), "Provenance", "config_revision is not positive")
  );

  /// The runtime request records are not part of the document model: a missing argument is a
  /// `NullPointerException` naming it, a blank name an `IllegalArgumentException`, and a null
  /// element what `List.copyOf` throws.
  private record RuntimeRefusal(String what, Executable construct, Class<? extends RuntimeException> type, String message) {
  }

  private static final List<RuntimeRefusal> RUNTIME_REFUSALS = List.of(
      new RuntimeRefusal("a request without a proxy program", () -> new SuppliedAccountsRequest(null, PROGRAM, "s", "h", List.of(), INSTRUCTION), NullPointerException.class, "proxyProgram"),
      new RuntimeRefusal("a request without a program", () -> new SuppliedAccountsRequest(PROXY, null, "s", "h", List.of(), INSTRUCTION), NullPointerException.class, "program"),
      new RuntimeRefusal("a request without a source", () -> new SuppliedAccountsRequest(PROXY, PROGRAM, null, "h", List.of(), INSTRUCTION), NullPointerException.class, "source"),
      new RuntimeRefusal("a request with a blank source", () -> new SuppliedAccountsRequest(PROXY, PROGRAM, " ", "h", List.of(), INSTRUCTION), IllegalArgumentException.class, "source is blank"),
      new RuntimeRefusal("a request without a handler", () -> new SuppliedAccountsRequest(PROXY, PROGRAM, "s", null, List.of(), INSTRUCTION), NullPointerException.class, "handler"),
      new RuntimeRefusal("a request with a blank handler", () -> new SuppliedAccountsRequest(PROXY, PROGRAM, "s", "", List.of(), INSTRUCTION), IllegalArgumentException.class, "handler is blank"),
      new RuntimeRefusal("a request with null roles", () -> new SuppliedAccountsRequest(PROXY, PROGRAM, "s", "h", null, INSTRUCTION), NullPointerException.class, "roles"),
      new RuntimeRefusal("a request with a null role", () -> new SuppliedAccountsRequest(PROXY, PROGRAM, "s", "h", withNull(), INSTRUCTION), NullPointerException.class, null),
      new RuntimeRefusal("a request without an instruction", () -> new SuppliedAccountsRequest(PROXY, PROGRAM, "s", "h", List.of(), null), NullPointerException.class, "instruction"),
      new RuntimeRefusal("a role without a name", () -> new SuppliedAccountsRequest.Role(null, List.of(), false), NullPointerException.class, "role"),
      new RuntimeRefusal("a role with a blank name", () -> new SuppliedAccountsRequest.Role(" ", List.of(), false), IllegalArgumentException.class, "role is blank"),
      new RuntimeRefusal("a role with null addresses", () -> new SuppliedAccountsRequest.Role("r", null, false), NullPointerException.class, "of"),
      new RuntimeRefusal("a role with a null address", () -> new SuppliedAccountsRequest.Role("r", withNull(), false), NullPointerException.class, null)
  );

  @TestFactory
  Stream<DynamicTest> runtimeRequestsRefuseOnConstruction() {
    return RUNTIME_REFUSALS.stream().map(refusal -> DynamicTest.dynamicTest(refusal.what(), () -> {
      final var e = assertThrows(refusal.type(), refusal.construct(), refusal.what());
      if (refusal.message() != null) {
        assertEquals(refusal.message(), e.getMessage(), refusal.what());
      }
    }));
  }

  /// Blank is what the parser reads as blank (JavaScript's `trim`): U+001C is a name to both,
  /// a no-break space to neither, so a name the parser admitted builds a request.
  @Test
  void runtimeRequestsReadBlankAsTheParserDoes() {
    assertEquals("\u001c", new SuppliedAccountsRequest.Role("\u001c", List.of(), false).role());
    assertEquals("\u001c", new SuppliedAccountsRequest(PROXY, PROGRAM, "\u001c", "\u001c", List.of(), INSTRUCTION).source());
    assertThrows(IllegalArgumentException.class, () -> new SuppliedAccountsRequest.Role("\u00a0", List.of(), false));
    assertThrows(IllegalArgumentException.class, () -> new SuppliedAccountsRequest(PROXY, PROGRAM, "\u00a0", "h", List.of(), INSTRUCTION));
  }

  @Test
  void runtimeRequestsCopyTheirLists() {
    final var roles = new ArrayList<SuppliedAccountsRequest.Role>();
    roles.add(new SuppliedAccountsRequest.Role("r", new ArrayList<>(List.of(PROGRAM)), true));
    final var request = new SuppliedAccountsRequest(PROXY, PROGRAM, "s", "h", roles, INSTRUCTION);
    roles.clear();
    assertEquals(1, request.roles().size());
    assertThrows(UnsupportedOperationException.class, () -> request.roles().clear());
    assertThrows(UnsupportedOperationException.class, () -> request.roles().getFirst().of().clear());
  }

  @TestFactory
  Stream<DynamicTest> refusesOnConstruction() {
    return REFUSALS.stream().map(refusal -> DynamicTest.dynamicTest(refusal.what(), () -> {
      final var e = assertThrows(MappingDocumentException.class, refusal.construct(), refusal.what());
      assertEquals(refusal.at(), e.at(), refusal.what());
      assertEquals(refusal.detail(), e.detail(), refusal.what());
      assertEquals(refusal.at() + ": " + refusal.detail(), e.getMessage());
    }));
  }

  @Test
  void admitsSoundRecords() {
    final var mapped = new InstructionEntry.Mapped("m", DISC, HANDLER, List.of(SOURCE), List.of(SEAT), RemainingAccounts.NONE);
    final var passthrough = new InstructionEntry.Passthrough("p", Discriminator.createDiscriminator(new byte[]{2}), "r");
    final var unsupported = new InstructionEntry.Unsupported("u", Discriminator.createDiscriminator(new byte[]{3}), "r");
    final var document = new MappingDocument(1, "test", PROGRAM, PROXY, null, List.of(mapped, passthrough, unsupported));
    assertEquals(3, document.instructions().size());
    assertEquals(0, new DestinationAccount.Dynamic(0, DynamicAccountName.GLAM_VAULT, true, false).index());
    assertEquals(PROXY, new DestinationAccount.Static(7, PROXY, false, false).address());
    assertEquals(3, new DestinationAccount.Source(2, 3, false, false, true).source());
    assertEquals(DynamicAccountName.GLAM_SIGNER, new Expectation.Dynamic(DynamicAccountName.GLAM_SIGNER).name());
    assertEquals(PROGRAM, new Expectation.Address(PROGRAM).address());
    assertEquals(1, new Provenance(null, null, null, 1).configRevision());
    assertEquals("g", new Provenance("g", "s", "p", 7).generator());
    assertEquals(1, InstructionMapper.createMapper(List.of(document)).documents().size());
  }

  /// The lists a record was built from are copied: a caller's later change does not reach
  /// the record, and the record's lists cannot be changed.
  @Test
  void holdsItsOwnCopies() {
    final var sources = new ArrayList<>(List.of(SOURCE));
    final var seats = new ArrayList<>(List.of(SEAT));
    final var mapped = new InstructionEntry.Mapped("m", DISC, HANDLER, sources, seats, RemainingAccounts.ANY);
    sources.clear();
    seats.clear();
    assertEquals(List.of(SOURCE), mapped.sourceAccounts());
    assertEquals(List.of(SEAT), mapped.destinationAccounts());
    assertThrows(UnsupportedOperationException.class, () -> mapped.sourceAccounts().clear());
    assertEquals(List.of(), mapped.suppliedAccounts());
    final var positions = new ArrayList<>(List.of(1, 2));
    final var supplied = new SuppliedAccount("r", positions, true);
    positions.clear();
    assertEquals(List.of(1, 2), supplied.of());
    assertThrows(UnsupportedOperationException.class, () -> supplied.of().clear());
    final var suppliedList = new ArrayList<>(List.of(supplied));
    final var withSupplied = new InstructionEntry.Mapped("m", DISC, HANDLER, List.of(), List.of(), RemainingAccounts.ANY, suppliedList);
    suppliedList.clear();
    assertEquals(List.of(supplied), withSupplied.suppliedAccounts());
    assertThrows(UnsupportedOperationException.class, () -> withSupplied.suppliedAccounts().clear());
    final var entries = new ArrayList<InstructionEntry>(List.of(mapped));
    final var document = new MappingDocument(1, "test", PROGRAM, PROXY, null, entries);
    entries.clear();
    assertEquals(List.of(mapped), document.instructions());
    assertThrows(UnsupportedOperationException.class, () -> document.instructions().add(mapped));
  }

  /// A discriminator is the record's own copy: the array it was built from, or an
  /// implementation that answers differently later, does not change it.
  @Test
  void holdsItsOwnDiscriminator() {
    final byte[] raw = {1};
    final var entry = new InstructionEntry.Passthrough("p", Discriminator.createDiscriminator(raw), "r");
    final var handler = new Handler("h", Discriminator.createDiscriminator(raw));
    raw[0] = 2;
    assertArrayEquals(new byte[]{1}, entry.discriminator().data());
    assertArrayEquals(new byte[]{1}, handler.discriminator().data());
    final byte[][] answers = {{1}, {2}};
    final int[] asked = {0};
    final Discriminator shifting = () -> answers[Math.min(asked[0]++, 1)];
    final var mapped = new InstructionEntry.Mapped("m", shifting, HANDLER, List.of(SOURCE), List.of(SEAT), RemainingAccounts.ANY);
    assertArrayEquals(new byte[]{1}, mapped.discriminator().data());
    assertArrayEquals(new byte[]{1}, mapped.discriminator().data());
    final byte[] handedOut = {1};
    final Discriminator lending = () -> handedOut;
    final var lent = new Handler("h", lending);
    handedOut[0] = 3;
    assertArrayEquals(new byte[]{1}, lent.discriminator().data(), "the record holds a clone, not the array the implementation handed out");
    final Discriminator nothing = () -> null;
    final var e = assertThrows(MappingDocumentException.class, () -> new Handler("h", nothing));
    assertEquals("Handler: the handler lacks a discriminator", e.getMessage());
  }
}
