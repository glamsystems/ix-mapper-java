package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import systems.comodal.jsoniter.JsonIterator;

import java.util.HashMap;

/// Jazzer entry point for the mapping-config JSON parser — the exact path
/// `ProgramMapConfig.createProxies` drives over every `*.json` file in the
/// untracked `glam/` download (`downloadMappings.sh`). Those files are
/// external input: fetched from `glamsystems/ix-mapper-ts` and parsed on the
/// account-remapping hot path by every consumer of this library.
///
/// The fuzz payload is arbitrary bytes parsed as the config JSON, exactly as
/// `createProxies` parses a file's bytes: `JsonIterator.parse(bytes)` then
/// `ProgramMapConfig.parseConfig`, then `createProgramProxies` — the full
/// construction path a consumer drives at startup, including the
/// `IxMapConfig.createProxy` mapping validation. Malformed-input contract:
/// garbage in -> `RuntimeException` out (a bad config file is a startup
/// failure, not a hang). Jazzer flags what the contract forbids — hangs
/// (deeply nested JSON, huge number literals), memory exhaustion, and any
/// non-`RuntimeException` throwable.
///
/// Seeded from real mapping configs under
/// src/test/resources/fuzz/mappingConfig — the nested instruction/account
/// structure is unreachable from scratch, so a mutator only makes progress
/// from a real seed.
///
/// Deliberately free of Jazzer imports so it compiles with the regular test
/// sources.
///
/// Run with `./gradlew :ix-proxy:fuzzMappingConfig [-PmaxFuzzTime=<seconds>]`.
public final class MappingConfigFuzz {

  private static final AccountMeta DEFAULT_INVOKED =
      AccountMeta.createInvoked(PublicKey.createPubKey(new byte[PublicKey.PUBLIC_KEY_LENGTH]));

  public static void fuzzerTestOneInput(final byte[] data) {
    final ProgramMapConfig config;
    try {
      final var ji = JsonIterator.parse(data);
      final var accountMetaCache = new HashMap<AccountMeta, AccountMeta>();
      final var indexedAccountMetaCache = new HashMap<IndexedAccountMeta, IndexedAccountMeta>();
      config = ProgramMapConfig.parseConfig(accountMetaCache, indexedAccountMetaCache, ji);
    } catch (final RuntimeException tolerated) {
      // malformed or truncated config JSON — rejection is in contract
      return;
    }
    if (config == null) {
      return;
    }
    // touch the parsed structure the way the mapper does, so a config that
    // parses into a nonsense shape (negative counts, dangling indices) surfaces
    // here rather than at first use on the remapping path
    config.invokedProxyProgram();
    config.ixMapConfigs().forEach(ix -> {
      ix.cpiDiscriminator();
      ix.proxyDiscriminator();
      ix.dynamicAccounts();
      ix.staticAccounts();
    });
    try {
      // a config that parses but describes an invalid mapping shape must also
      // be rejected here, at construction — still a startup failure, not a
      // hang or a deferred blow-up on the remapping path
      config.createProgramProxies(DEFAULT_INVOKED, DynamicAccountConfig::<Void>createFeePayerAccount)
          .forEach(ProgramProxy::cpiProgram);
    } catch (final RuntimeException tolerated) {
    }
  }
}
