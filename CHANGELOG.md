# Changelog

## [25.1.0](https://github.com/glamsystems/ix-mapper-java/compare/25.0.4...25.1.0) (2026-09-25)


### ⚠ BREAKING CHANGES

* **ix-proxy:** the index-map API is gone: ProgramMapConfig, IxMapConfig, DynamicAccountConfig, ConfigLoader and ConfigLoader.ConfigResource, TransactionMapper, ProgramProxy, IxProxy, IxMapper, DynamicAccount, IndexedAccountMeta, IndexedFeePayer and IndexedReadOnlyProgram. With them go unchecked mapping, remote config loading, custom DynamicAccount implementations and adding lookup tables while mapping; mapping-configs-v1 files are refused in favour of schema_version 1 documents. Consumers build an InstructionMapper from MappingDocuments and map with a MappingContext. mapTransaction has no fee-payer form: the transaction keeps its own. The module no longer requires json_iterator transitively; a consumer that reads JSON itself declares it.

### Features

* **ci:** add fuzzing workflow and enhance mutation testing ([3cf47aa](https://github.com/glamsystems/ix-mapper-java/commit/3cf47aaedbae068d84caca05daef17d790f01b4e))
* **ix-proxy:** map instructions from the mapping document ([f0ba4e8](https://github.com/glamsystems/ix-mapper-java/commit/f0ba4e80bd2c10bd4231eedb4d9632c7f410557a))
* **ix-proxy:** rewrite optional-account None sentinels to the proxy program id ([524ce18](https://github.com/glamsystems/ix-mapper-java/commit/524ce18c1a05387dda52ef2530425d3f1a8564f2))
* **ix-proxy:** update PIT toolchain and hardening configuration ([dc4b473](https://github.com/glamsystems/ix-mapper-java/commit/dc4b473196449a3c64831236182b405f363472ee))
* **test:** add unit tests for ix-proxy config parsing and validation ([b021f38](https://github.com/glamsystems/ix-mapper-java/commit/b021f3899e788523daea662ef5f48b8752d98420))
* **test:** validate any mappings root via -PglamMappingsDir ([71b00a2](https://github.com/glamsystems/ix-mapper-java/commit/71b00a2a08cd7ad4b6456c776a42280d425877fd))


### Bug Fixes

* **ci:** restrict workflow token permissions ([81f4119](https://github.com/glamsystems/ix-mapper-java/commit/81f41191dbbcdd0511ca3e701d5119ec3879b6f1))
* **ix-mapper:** the six Kamino handlers that take no remaining accounts are none (GLAM-1423) ([#1434](https://github.com/glamsystems/ix-mapper-java/issues/1434)) ([cea4c39](https://github.com/glamsystems/ix-mapper-java/commit/cea4c394fa728823e49c3e83e4f3428322bab4c3))
* **ix-proxy:** publish the developer id, name and email without quotes ([b8c2c52](https://github.com/glamsystems/ix-mapper-java/commit/b8c2c52ef03ca8e62621373662cc99d47a003e6c))
* replace finalized transaction targets with confirmed ([#1423](https://github.com/glamsystems/ix-mapper-java/issues/1423)) ([189c433](https://github.com/glamsystems/ix-mapper-java/commit/189c433bd4b9fd07060d44da65a63485fe8892ce))


### Miscellaneous Chores

* release 25.1.0 ([c3c57d5](https://github.com/glamsystems/ix-mapper-java/commit/c3c57d52a5f02d1029c67a665288892a0dc021e4))

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
