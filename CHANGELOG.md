# Changelog

## [25.2.0](https://github.com/glamsystems/ix-mapper-java/compare/25.1.1...25.2.0) (2026-10-05)


### ⚠ BREAKING CHANGES

* **ix-proxy:** DestinationAccount gains the permit Supplied, so an exhaustive switch over it must take the new kind; SuppliedAccountsRequest.Role gains a fourth component, derivation (the three-argument constructor stays); SuppliedAccountsRequest gains the nested Derivation, Seed, Const, Account and Arg, and the document model the record Derivation with its sealed Seed.

### Features

* **ix-proxy:** a supplied account at the account index its handler declares, with the derivation the IDL states ([6013cb4](https://github.com/glamsystems/ix-mapper-java/commit/6013cb41ef1a30ef1ee1d4b33dee510574d67f2b))


### Bug Fixes

* **build:** bump solanaBOMVersion to 25.30.32 ([edc3f6d](https://github.com/glamsystems/ix-mapper-java/commit/edc3f6df326c79ccf9ce1588f33de36bf0d574cc))

## [25.1.1](https://github.com/glamsystems/ix-mapper-java/compare/25.1.0...25.1.1) (2026-09-28)


### Bug Fixes

* **ix-proxy:** keep a caller-chosen signer's flag at an unsigned seat and require it at a signing seat ([c948d58](https://github.com/glamsystems/ix-mapper-java/commit/c948d58ef5cf7dfe96362a4506ab635e464047cc))

## [25.1.0](https://github.com/glamsystems/ix-mapper-java/compare/25.0.4...25.1.0) (2026-09-26)


### ⚠ BREAKING CHANGES

* **ix-proxy:** `MappingContext` gains a fifth component, the supplier (the three- and four-argument constructors stay); `InstructionEntry.Mapped` gains `suppliedAccounts` (the six-argument constructor stays); `UnsupportedReason` gains `SUPPLIED_ACCOUNTS`, so an exhaustive switch over it must take the new constant; `SuppliedAccountsRequest` and `Role` throw runtime exceptions, not `MappingDocumentException`, on a bad argument.
* **ix-proxy:** the index-map API is gone: ProgramMapConfig, IxMapConfig, DynamicAccountConfig, ConfigLoader and ConfigLoader.ConfigResource, TransactionMapper, ProgramProxy, IxProxy, IxMapper, DynamicAccount, IndexedAccountMeta, IndexedFeePayer and IndexedReadOnlyProgram. With them go unchecked mapping, remote config loading, custom DynamicAccount implementations and adding lookup tables while mapping; mapping-configs-v1 files are refused in favour of schema_version 1 documents. Consumers build an InstructionMapper from MappingDocuments and map with a MappingContext. mapTransaction has no fee-payer form: the transaction keeps its own. The module no longer requires json_iterator transitively; a consumer that reads JSON itself declares it.

### Features

* **ci:** add fuzzing workflow and enhance mutation testing ([3cf47aa](https://github.com/glamsystems/ix-mapper-java/commit/3cf47aaedbae068d84caca05daef17d790f01b4e))
* **ix-proxy:** map instructions from the mapping document ([f0ba4e8](https://github.com/glamsystems/ix-mapper-java/commit/f0ba4e80bd2c10bd4231eedb4d9632c7f410557a))
* **ix-proxy:** rewrite optional-account None sentinels to the proxy program id ([524ce18](https://github.com/glamsystems/ix-mapper-java/commit/524ce18c1a05387dda52ef2530425d3f1a8564f2))
* **ix-proxy:** supplied accounts on a mapped entry (GLAM-1447) ([d69cde6](https://github.com/glamsystems/ix-mapper-java/commit/d69cde6812c1836078d47a847d5fa71d51b29e32))
* **ix-proxy:** update PIT toolchain and hardening configuration ([dc4b473](https://github.com/glamsystems/ix-mapper-java/commit/dc4b473196449a3c64831236182b405f363472ee))
* **test:** add unit tests for ix-proxy config parsing and validation ([b021f38](https://github.com/glamsystems/ix-mapper-java/commit/b021f3899e788523daea662ef5f48b8752d98420))
* **test:** validate any mappings root via -PglamMappingsDir ([71b00a2](https://github.com/glamsystems/ix-mapper-java/commit/71b00a2a08cd7ad4b6456c776a42280d425877fd))


### Bug Fixes

* **build:** bump solanaBOMVersion to 25.30.27 ([e3c75ea](https://github.com/glamsystems/ix-mapper-java/commit/e3c75ea200c878ad385d100c0d73b709410a5162))
* **ci:** restrict workflow token permissions ([81f4119](https://github.com/glamsystems/ix-mapper-java/commit/81f41191dbbcdd0511ca3e701d5119ec3879b6f1))
* **ix-proxy:** publish the developer id, name and email without quotes ([b8c2c52](https://github.com/glamsystems/ix-mapper-java/commit/b8c2c52ef03ca8e62621373662cc99d47a003e6c))


### Documentation

* name 25.1.0 for the supplied-accounts contract, and say how a version is named here ([b91cf15](https://github.com/glamsystems/ix-mapper-java/commit/b91cf15985bba96ca1fdbbd4599aef10ec3485d9))

## [25.0.4](https://github.com/glamsystems/ix-mapper-java/compare/25.0.3...25.0.4) (2026-05-31)


### Features

* trigger release ([064716b](https://github.com/glamsystems/ix-mapper-java/commit/064716b0b28eb70839adb4efdc9abbbb15aa6d3c))

## [25.0.3](https://github.com/glamsystems/ix-mapper-java/compare/25.0.2...25.0.3) (2026-05-31)


### ⚠ BREAKING CHANGES

* **test:** New test structure and staging-specific handling introduce constraints requiring updated configurations.

### Features

* **test:** add comprehensive tests for ix-proxy and staging handling ([cd6ca66](https://github.com/glamsystems/ix-mapper-java/commit/cd6ca6602a0528da388ed9028cd7e97b57d6ec35))


### Bug Fixes

* **ci:** update release-please workflow with stricter repo validation ([65cb3b7](https://github.com/glamsystems/ix-mapper-java/commit/65cb3b7203b9b1c7d3946f9bddb934496909c717))
* **test:** adjust account meta test to use getFirst() ([65cb3b7](https://github.com/glamsystems/ix-mapper-java/commit/65cb3b7203b9b1c7d3946f9bddb934496909c717))
