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
