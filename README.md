# ixProxy [![Gradle Check](https://github.com/glamsystems/ix-proxy/actions/workflows/build.yml/badge.svg)](https://github.com/glamsystems/ix-proxy/actions/workflows/build.yml) [![Publish Release](https://github.com/glamsystems/ix-proxy/actions/workflows/publish.yml/badge.svg)](https://github.com/glamsystems/ix-proxy/actions/workflows/publish.yml)

Facilitates the re-mapping of instructions from one program to another proxy program. The primary use case is to add
additional safety checks in the proxy program before and after forwarding the request to the original program.

## [Transaction Mapper](https://github.com/glamsystems/ix-proxy/blob/main/ix-proxy/src/main/java/systems/glam/ix/proxy/TransactionMapper.java)

The transaction mapper can be used to map a list of instructions or entire transactions.

### Example Construction

A dynamic account factory is needed from the user to provide the functions for wiring runtime accounts.

This example uses a simple version that could be used for the GLAM proxy program.

```java
record GlamVaultAccounts(AccountMeta readGlamState,
                         AccountMeta writeGlamState,
                         AccountMeta readGlamVault,
                         AccountMeta writeGlamVault) {

  static GlamVaultAccounts createAccounts(final PublicKey stateAccount, final PublicKey vaultAccount) {
    return new GlamVaultAccounts(
        AccountMeta.createRead(stateAccount),
        AccountMeta.createWrite(stateAccount),
        AccountMeta.createRead(vaultAccount),
        AccountMeta.createWrite(vaultAccount)
    );
  }
}

Function<DynamicAccountConfig, DynamicAccount<GlamVaultAccounts>> dynamicAccountFactory = accountConfig -> {
  final int index = accountConfig.index();
  final boolean w = accountConfig.writable();
  return switch (accountConfig.name()) {
    case "glam_state" -> (mappedAccounts, _, _, vaultAccounts) -> mappedAccounts[index] = w
        ? vaultAccounts.writeGlamState() : vaultAccounts.readGlamState();
    case "glam_vault" -> (mappedAccounts, _, _, vaultAccounts) -> mappedAccounts[index] = w
        ? vaultAccounts.writeGlamVault() : vaultAccounts.readGlamVault();
    case "glam_signer" -> accountConfig.createFeePayerAccount();
    case "cpi_program" -> accountConfig.createReadCpiProgram();
    default -> throw new IllegalStateException("Unknown dynamic account type: " + accountConfig.name());
  };
```

The following iterates over each mapping configuration file in a given directory.
Constructs the corresponding program proxy for each and puts them in a map with the key being the source CPI program. 
Then finally the TransactionMapper is constructed. 

```java
// Used to de-duplicate AccountMeta objects.
var accountMetaCache = new HashMap<AccountMeta, AccountMeta>(256);
var indexedAccountMetaCache = new HashMap<IndexedAccountMeta, IndexedAccountMeta>(256);

var proxyProgram = PublicKey.fromBase58Encoded("");
var invokedProxyProgram = AccountMeta.createInvoked(proxyProgram);
Function<DynamicAccountConfig, DynamicAccount<A>> dynamicAccountFactory = null; // See example above.

var programKeyToProgramProxyMap = new HashMap<PublicKey, ProgramProxy<A>>();

var mappingFileDirectory = Path.of("path/to/mapping/config/files");
try (final var paths = Files.walk(mappingFileDirectory, 1)) {
  paths
      .filter(Files::isRegularFile)
      .filter(Files::isReadable)
      .filter(f -> f.getFileName().toString().endsWith(".json"))
      .forEach(mappingFile -> ProgramMapConfig.createProxies(
          mappingFile,
          invokedProxyProgram,
          programKeyToProgramProxyMap,
          dynamicAccountFactory,
          accountMetaCache,
          indexedAccountMetaCache
      ));
  
  var txMapper = TransactionMapper.createMapper(invokedProxyProgram, programProxies);
}
```

### Example Usage

```java 
// Given some instructions or a transaction from any source.
var sourceInstructions = List.<Instruction>of();
var feePayer = AccountMeta.createFeePayer(PublicKey.fromBase58Encoded(""));
A runtimeAccounts = null; // See example above.

Instruction[] mappedInstructions = txMapper.mapInstructions(
    feePayer, 
    runtimeAccounts,
    sourceInstructions
);
```

## Program Mapping Configuration Files

Mapping files define the necessary information for translating a source program instruction that will be called via CPI
from the destination proxy program.  More example configurations can be found in the [ix-mapper-ts repository](https://github.com/glamsystems/ix-mapper-ts)

### Example Configuration

```json
{
  "program_id": "dRiftyHA39MWEi3m9aunc5MzRF1JYuBsbn6VPcn33UH",
  "instructions": [
    {
      "src_ix_name": "cancel_orders",
      "src_discriminator": [
        238,
        225,
        95,
        158,
        227,
        103,
        8,
        194
      ],
      "dst_ix_name": "drift_cancel_orders",
      "dst_discriminator": [
        98,
        107,
        48,
        79,
        97,
        60,
        99,
        58
      ],
      "dynamic_accounts": [
        {
          "name": "glam_state",
          "index": 0,
          "writable": false,
          "signer": false
        },
        {
          "name": "glam_vault",
          "index": 1,
          "writable": false,
          "signer": false
        },
        {
          "name": "glam_signer",
          "index": 2,
          "writable": true,
          "signer": true
        },
        {
          "name": "cpi_program",
          "index": 3,
          "writable": false,
          "signer": false
        }
      ],
      "static_accounts": [],
      "index_map": [
        4,
        5,
        -1
      ]
    }
  ]
}
```

### **src_discriminator**

The discriminator of the original instruction.

### **dst_discriminator**

The discriminator of the proxy instruction.

### **dynamic_accounts**

Account Meta information for accounts which can differ at runtime.

### **static_accounts**

Account Meta information for accounts which are always the same given a Solana cluster such as mainnet.

### **index_map**

Defines the parameter index for the destination instruction. If the account has been removed or replaced use a negative
number.

## Build & Tests

```shell
./gradlew check
```

Mapping configuration files from the [ix-mapper-ts repository](https://github.com/glamsystems/ix-mapper-ts)
are needed to run the tests. The build clones them into the untracked `glam/` directory automatically when it is
missing (see [./downloadMappings.sh](downloadMappings.sh)).

### Sync Re-mapping JSON Files

```shell
./syncMappings.sh
```

## Hardening

The `ix-proxy` module registers the PIT mutation suite `pitestIxProxy` and the Jazzer fuzz target `fuzzMappingConfig`
via sava-build's hardening feature; unkilled mutants are ratcheted against the accepted baseline in
[ix-proxy/config/pitest](ix-proxy/config/pitest). See [AGENTS.md](AGENTS.md) for the process contract.
