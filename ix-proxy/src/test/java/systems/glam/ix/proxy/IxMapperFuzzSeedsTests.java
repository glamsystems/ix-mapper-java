package systems.glam.ix.proxy;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static systems.glam.ix.proxy.IxMapperFuzz.*;

/// Each committed fuzz seed reaches the outcome its name promises, so a seed pins a path and
/// not merely a byte pattern the harness happens to tolerate.
final class IxMapperFuzzSeedsTests {

  private static final Path SEEDS = Path.of("src/test/resources/fuzz/ixMapper");

  /// A seed's outcome: the result kind and the entry it names, the refusal's reason and
  /// message or the passthrough's reason, and the times the supplier was asked.
  private record Outcome(Class<? extends MapResult> kind,
                         String source,
                         UnsupportedReason reason,
                         String message,
                         int asks) {
  }

  private static Outcome mapped(final String source, final int asks) {
    return new Outcome(MapResult.Mapped.class, source, null, null, asks);
  }

  private static Outcome passthrough(final String source, final String reason) {
    return new Outcome(MapResult.Passthrough.class, source, null, reason, 0);
  }

  private static Outcome refused(final String source, final UnsupportedReason reason, final String message, final int asks) {
    return new Outcome(MapResult.Unsupported.class, source, reason, message, asks);
  }

  private static final String PRICED_COUNT = "priced takes 2 to 3 supplied accounts (price_oracle, reserve, market?); the context supplied ";
  private static final String ROUTED_COUNT = "routed takes 2 to 3 supplied accounts (routes, ledger, oracle?); the context supplied ";

  private static final Map<String, Outcome> EXPECTED = Map.ofEntries(
      Map.entry("a-full-every-position", mapped("full", 0)),
      Map.entry("a-full-omitted-trailing", mapped("full", 0)),
      Map.entry("a-full-sentinel-rewrite", mapped("full", 0)),
      Map.entry("a-full-remaining-accounts", mapped("full", 0)),
      Map.entry("a-full-no-supplier", mapped("full", 0)),
      Map.entry("a-full-too-few", refused("full", UnsupportedReason.ACCOUNT_COUNT,
          "full needs account 1 (thing); the instruction carries 1", 0)),
      Map.entry("a-full-wrong-owner", refused("full", UnsupportedReason.ACCOUNT_EXPECTATION,
          "full account 0 (owner) must be glam_vault", 0)),
      Map.entry("a-full-signing-thing", refused("full", UnsupportedReason.ACCOUNT_PRIVILEGE,
          "full account 1 (thing) signs, but the handler takes it unsigned", 0)),
      Map.entry("a-priced-all", mapped("priced", 1)),
      Map.entry("a-priced-required-only", mapped("priced", 1)),
      Map.entry("a-priced-no-supplier", refused("priced", UnsupportedReason.CONTEXT,
          "the context supplies no accounts for priced", 0)),
      Map.entry("a-priced-null-answer", refused("priced", UnsupportedReason.CONTEXT,
          "the context supplies no accounts for priced", 1)),
      Map.entry("a-priced-supplier-throws", refused("priced", UnsupportedReason.CONTEXT,
          "the context's supplied accounts failed for priced: the supplier cannot answer", 1)),
      Map.entry("a-priced-too-few", refused("priced", UnsupportedReason.SUPPLIED_ACCOUNTS, PRICED_COUNT + 1, 1)),
      Map.entry("a-priced-too-many", refused("priced", UnsupportedReason.SUPPLIED_ACCOUNTS, PRICED_COUNT + 4, 1)),
      Map.entry("a-priced-null-element", refused("priced", UnsupportedReason.SUPPLIED_ACCOUNTS,
          "the context supplied a null account at 2 for priced", 1)),
      Map.entry("a-routed-all", mapped("routed", 1)),
      Map.entry("a-routed-required-only", mapped("routed", 1)),
      Map.entry("a-routed-sentinel-rewrite", mapped("routed", 1)),
      Map.entry("a-routed-remaining-accounts", mapped("routed", 1)),
      Map.entry("a-routed-no-supplier", refused("routed", UnsupportedReason.CONTEXT,
          "the context supplies no accounts for routed", 0)),
      Map.entry("a-routed-too-few", refused("routed", UnsupportedReason.SUPPLIED_ACCOUNTS, ROUTED_COUNT + 1, 1)),
      Map.entry("a-routed-null-element", refused("routed", UnsupportedReason.SUPPLIED_ACCOUNTS,
          "the context supplied a null account at 2 for routed", 1)),
      Map.entry("a-passthrough", passthrough("read", "nothing signs")),
      Map.entry("a-refused", refused("other", UnsupportedReason.REFUSED_INSTRUCTION, "no handler", 0)),
      Map.entry("a-unknown-discriminator", refused(null, UnsupportedReason.UNKNOWN_INSTRUCTION,
          "no instruction of " + PROGRAM_A.toBase58() + " matches the data", 0)),
      Map.entry("a-empty-data", refused(null, UnsupportedReason.UNKNOWN_INSTRUCTION,
          "no instruction of " + PROGRAM_A.toBase58() + " matches the data", 0)),
      Map.entry("a-span-past-the-buffer", refused(null, UnsupportedReason.UNREADABLE_INSTRUCTION,
          "the instruction's data span (offset 0, length 9) lies outside its buffer of 2 bytes", 0)),
      Map.entry("b-strict-exact", mapped("strict", 0)),
      Map.entry("b-strict-signing-thing", mapped("strict", 0)),
      Map.entry("b-strict-unsigned-payer", refused("strict", UnsupportedReason.ACCOUNT_PRIVILEGE,
          "strict account 0 (payer) must sign", 0)),
      Map.entry("b-strict-extra-account", refused("strict", UnsupportedReason.REMAINING_ACCOUNTS,
          "strict takes no accounts beyond its 2; the instruction carries 3", 0)),
      Map.entry("b-strict-short-discriminator", refused(null, UnsupportedReason.UNKNOWN_INSTRUCTION,
          "no instruction of " + PROGRAM_B.toBase58() + " matches the data", 0)),
      Map.entry("unknown-program", passthrough(null, "the program has no mapping document"))
  );

  /// An account as the mapped instruction holds it.
  private record Meta(PublicKey key, boolean write, boolean signer) {

    static Meta of(final AccountMeta account) {
      return new Meta(account.publicKey(), account.write(), account.signer());
    }
  }

  private static Meta read(final PublicKey key) {
    return new Meta(key, false, false);
  }

  private static Meta write(final PublicKey key) {
    return new Meta(key, true, false);
  }

  private static Meta writableSigner(final PublicKey key) {
    return new Meta(key, true, true);
  }

  private static Meta readSigner(final PublicKey key) {
    return new Meta(key, false, true);
  }

  private static PublicKey suppliedAnswer(final int i) {
    return PRICED_SUPPLIED.get(i).answer();
  }

  private static PublicKey routedAnswer(final int i) {
    return ROUTED_ANSWERS.get(i);
  }

  /// The whole mapped account list of every seed that maps.
  private static final Map<String, List<Meta>> ACCOUNTS = Map.ofEntries(
      // the eight seats of `full`
      Map.entry("a-full-every-position", List.of(
          read(STATE), write(VAULT), writableSigner(SIGNER), read(AUTHORITY), read(PROGRAM_A),
          write(SIGNER), read(POOL[2]), read(POOL[3])
      )),
      // three accounts: the seat forwarding the omittable position 3 is left out
      Map.entry("a-full-omitted-trailing", List.of(
          read(STATE), write(VAULT), writableSigner(SIGNER), read(AUTHORITY), read(PROGRAM_A),
          write(SIGNER), read(POOL[2])
      )),
      // position 2 holds program A, so the sentinel seat 6 holds the proxy program
      Map.entry("a-full-sentinel-rewrite", List.of(
          read(STATE), write(VAULT), writableSigner(SIGNER), read(AUTHORITY), read(PROGRAM_A),
          write(SIGNER), read(PROXY), read(POOL[3])
      )),
      // the eight seats, then accounts 4 to 6 as they came
      Map.entry("a-full-remaining-accounts", List.of(
          read(STATE), write(VAULT), writableSigner(SIGNER), read(AUTHORITY), read(PROGRAM_A),
          write(SIGNER), read(POOL[2]), read(POOL[3]),
          read(POOL[4]), read(POOL[5]), read(POOL[6])
      )),
      // seat 0 forwards position 1, seat 1 the signing payer at position 0
      Map.entry("b-strict-exact", List.of(read(SIGNER), writableSigner(VAULT))),
      // the caller-chosen signer at position 1 signs, and its unsigned seat 0 keeps the flag
      Map.entry("b-strict-signing-thing", List.of(readSigner(SIGNER), writableSigner(VAULT))),
      // five seats, the three supplied accounts, the two accounts beyond the list
      Map.entry("a-priced-all", List.of(
          read(STATE), write(VAULT), writableSigner(SIGNER), write(SIGNER), read(VAULT),
          read(suppliedAnswer(0)), read(suppliedAnswer(1)), read(suppliedAnswer(2)),
          writableSigner(POOL[2]), write(POOL[3])
      )),
      // five seats, the two required supplied accounts
      Map.entry("a-priced-required-only", List.of(
          read(STATE), write(VAULT), writableSigner(SIGNER), write(SIGNER), read(VAULT),
          read(suppliedAnswer(0)), read(suppliedAnswer(1))
      )),
      // the eight seats and nothing after them
      Map.entry("a-full-no-supplier", List.of(
          read(STATE), write(VAULT), writableSigner(SIGNER), read(AUTHORITY), read(PROGRAM_A),
          write(SIGNER), read(POOL[2]), read(POOL[3])
      )),
      // the answers for `routes` at account index 2 and the writable `ledger` at 5 in place,
      // then the optional `oracle` the entry lists
      Map.entry("a-routed-all", List.of(
          write(STATE), read(PROGRAM_A), read(routedAnswer(0)), read(VAULT), read(SIGNER),
          write(routedAnswer(1)), read(POOL[2]), read(routedAnswer(2))
      )),
      // the two required answers in place, and the optional one left out
      Map.entry("a-routed-required-only", List.of(
          write(STATE), read(PROGRAM_A), read(routedAnswer(0)), read(VAULT), read(SIGNER),
          write(routedAnswer(1)), read(POOL[2])
      )),
      // position 2 holds program A, so account index 6 holds the proxy program, which is also
      // what the derivation of `routes` names there (the supplier checks it)
      Map.entry("a-routed-sentinel-rewrite", List.of(
          write(STATE), read(PROGRAM_A), read(routedAnswer(0)), read(VAULT), read(SIGNER),
          write(routedAnswer(1)), read(PROXY), read(routedAnswer(2))
      )),
      // the oracle the entry lists comes before accounts 3 and 4, which follow as they came
      Map.entry("a-routed-remaining-accounts", List.of(
          write(STATE), read(PROGRAM_A), read(routedAnswer(0)), read(VAULT), read(SIGNER),
          write(routedAnswer(1)), read(POOL[2]), read(routedAnswer(2)),
          read(POOL[3]), read(POOL[4])
      ))
  );

  @TestFactory
  Stream<DynamicTest> everySeedReachesItsOutcome() throws IOException {
    final var files = Files.list(SEEDS).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
    assertEquals(EXPECTED.keySet(), files.stream().map(path -> path.getFileName().toString()).collect(Collectors.toSet()),
        "every seed has an expected outcome and every expected outcome a seed");
    assertEquals(EXPECTED.entrySet().stream().filter(entry -> entry.getValue().kind() == MapResult.Mapped.class)
            .map(Map.Entry::getKey).collect(Collectors.toSet()), ACCOUNTS.keySet(),
        "every seed that maps pins its accounts, and only those");
    return files.stream().map(file -> DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
      final byte[] bytes;
      try {
        bytes = Files.readAllBytes(file);
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
      final var name = file.getFileName().toString();
      final var checked = mapChecked(bytes);
      assertNotNull(checked, "the seed carves an instruction");
      final var result = checked.result();
      final var expected = EXPECTED.get(name);
      assertInstanceOf(expected.kind(), result);
      assertEquals(expected.source(), result.source(), "the entry the result names");
      assertEquals(expected.asks(), checked.asks(), "the times the supplier was asked");
      switch (result) {
        case MapResult.Unsupported unsupported -> {
          assertEquals(expected.reason(), unsupported.reason());
          assertEquals(expected.message(), unsupported.message());
        }
        case MapResult.Passthrough passthrough -> assertEquals(expected.message(), passthrough.reason());
        case MapResult.Mapped mapped -> {
          final var accounts = mapped.instruction().accounts().stream().map(Meta::of).toList();
          final var pinned = ACCOUNTS.get(name);
          assertNotNull(pinned, "a seed that maps pins its accounts");
          assertEquals(pinned, accounts);
        }
      }
    }));
  }
}
