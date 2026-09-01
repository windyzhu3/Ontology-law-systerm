# PostgreSQL 18 v1 hosted runtime evidence

ID: DB-52P2-PG18-RUNTIME
Version: pg18-52-plus-2-v1
Command: python3 runtime/verify_runtime.py verify --ci-only --runs 2 --evidence-dir ../../.artifacts/schema-runtime
Exit code: 0

## Governance and provenance

- Decision: `ADR-0003`
- Repository: `windyzhu3/Ontology-law-systerm`
- Pull request: `#3`
- Workflow run: `33405965491`
- Artifact ID: `9763252627`
- Artifact ZIP SHA-256: `dc4a633aadf4faee4931dd782d4edd105add5078227d9f2a24f2fb4b2401e7fc`
- Base commit: `72a83b810339095a6ebefd11b30cf7fc8f522eec`
- Head commit: `d0cd39de079f69cbd3973ab59f9f4ff75732203c`
- Test merge commit: `ae0ec5d32fdc2e5db7276a9ba7ebbbeb2814a6c1`
- Test merge parents: `72a83b810339095a6ebefd11b30cf7fc8f522eec`, `d0cd39de079f69cbd3973ab59f9f4ff75732203c`
- Test merge object SHA-256: `21b8c6ceedb5ea86b3f0eaed169cc77f6a1c77bbf511ff13f588b631f86a33d2`

The object attestation hashes Git's canonical commit object bytes: `commit <payload-size>\0` followed by the exact commit payload.

## Exact v1 source binding

- Contract version: `52-plus-2-v1`
- Contract SHA-256: `a9c53d0126b7997e0aac511d3a4baf1da02a5f10d829ca5113458be51813034a`
- Field contract SHA-256: `be79d991fa9e13e3f0af1c682333b6a063201387b78f7c9ec32a03bad51096ed`
- V001-V840 tree SHA-256: `166e709e1068a3a5241ed4805e667a651454278ce4ed7ac93374c1c4f4cc81bf`

## Local execution truth

- Status: `BLOCKED`
- Reason code: `docker_compose_unavailable`
- Exit code: `5`

The local environment did not pass PostgreSQL runtime verification. ADR-0003 accepts the closed hosted two-run proof after exact artifact and source validation.

## Empty-database proof disclosure

The safe artifact intentionally does not publish the raw 32-hex catalog fingerprints. It publishes SHA-256 digests of the complete verifier outputs; equality below binds run A, run B, and run A no-op to the same validated output without reconstructing hidden bytes.

| Run | Raw catalog fingerprint published | Verifier output SHA-256 |
|---|---|---|
| `run-01` | `false` | `305bd96791d44cbb64d4046087a7a1d7486c9ffbca1b0d8130c5a8f3e9706975` |
| `run-02` | `false` | `305bd96791d44cbb64d4046087a7a1d7486c9ffbca1b0d8130c5a8f3e9706975` |

## Run A no-op proof

- Migrate exit code: `0`
- Migrate output SHA-256: `a18cdff4392c3e29be5c19846057b1348a365858a5ece214ee70808d7446ad28`
- Verifier output SHA-256: `305bd96791d44cbb64d4046087a7a1d7486c9ffbca1b0d8130c5a8f3e9706975`

## Closed hosted artifact

# PostgreSQL 18 runtime CI summary

- Outcome: `PASSED`
- Reason code: `runtime_verified`
- Git commit: `ae0ec5d32fdc2e5db7276a9ba7ebbbeb2814a6c1`

## Runs

| Run | Stage | Exit code | Timed out | Diagnostic | Stdout SHA-256 | Stderr SHA-256 |
|---|---|---:|---|---|---|---|
| `run-01` | `postgres-start` | `0` | `false` | `ok` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | `ed086c887441ff7d0be5932b2af0f5398a07b5eead2d207fb5d0fda9004e9ab2` |
| `run-01` | `flyway-start` | `0` | `false` | `ok` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | `c4d01a819fd3e2d8d800056b1bca0f9944b9252e16636c5a87e3318fc9b468c0` |
| `run-01` | `flyway-wait` | `0` | `false` | `ok` | `7469fe67db1ea78345d6f3934d90d1c14b40aabd021869667c3c5c1d01ac8710` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `run-01` | `flyway-status` | `0` | `false` | `ok` | `a9b1f212ef3591d0e4fe49a695d51af7ca536adc7ff7a60f424ed9f3a0b25568` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `run-01` | `verifier-start` | `0` | `false` | `ok` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | `006b74227fa1ef655c2efc7a2a644fc5f228dec5397bb4e792397132c97150ef` |
| `run-01` | `verifier-wait` | `0` | `false` | `ok` | `8bb163c51d8f33f7e951920f7dde0df0c3c4cbd74c9f8af8cc22da3fb68831e7` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `run-01` | `verifier-status` | `0` | `false` | `ok` | `8a3be64eccf03ff931c071d6dbc89ff56a251ea3e404d758e15d15ed9d12792d` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `run-01` | `verifier-logs` | `0` | `false` | `ok` | `305bd96791d44cbb64d4046087a7a1d7486c9ffbca1b0d8130c5a8f3e9706975` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `run-01` | `noop-migrate` | `0` | `false` | `ok` | `a18cdff4392c3e29be5c19846057b1348a365858a5ece214ee70808d7446ad28` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `run-01` | `noop-verifier-start` | `0` | `false` | `ok` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | `f58bbe0f9201c973d4b380f4c32942518c724157b4425992b4ed2c0a15cb0085` |
| `run-01` | `noop-verifier-wait` | `0` | `false` | `ok` | `ebaec1301be41e01d5f2cb7a230164875fa20f32a18f41730e8fdf7b840756cf` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `run-01` | `noop-verifier-status` | `0` | `false` | `ok` | `0e078b392df10638edad45a772c6e35c0282b9532c2f296b6e79d3f1ac2459fd` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `run-01` | `noop-verifier-logs` | `0` | `false` | `ok` | `305bd96791d44cbb64d4046087a7a1d7486c9ffbca1b0d8130c5a8f3e9706975` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `run-01` | `compose-down` | `0` | `false` | `ok` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | `c641b04cf1a361f3bd00474639986001acd672d0c04b10ead87b7e07dd232503` |
| `run-02` | `postgres-start` | `0` | `false` | `ok` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | `0b9912ee5d3054be25efa0edc278d3166585d845e1cfb5b93a6b2a730b840def` |
| `run-02` | `flyway-start` | `0` | `false` | `ok` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | `d105b081abc012007cb420854b24d018a16f210389077ac6f406eae57eb35251` |
| `run-02` | `flyway-wait` | `0` | `false` | `ok` | `ace521c5b7007e2e8771e719af43e29491e3b4d4eb93128b8c5d1640642b7fd2` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `run-02` | `flyway-status` | `0` | `false` | `ok` | `4749d495cb875b9a1f0ec374ae72f3e42a3b149db580d6f1a21ae540f121b5dc` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `run-02` | `verifier-start` | `0` | `false` | `ok` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | `64c41bc87f8ae4a11ef10247198a838de0cb74e873528683ae85b362c3fe92e7` |
| `run-02` | `verifier-wait` | `0` | `false` | `ok` | `2124fc99c489685899f063b40057631dd2045062d1c143df5aa9a76edf60ee5c` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `run-02` | `verifier-status` | `0` | `false` | `ok` | `5590292cf5aaa90cad96257daeff6e93161e62202ffda3abfa926c8d3cf96978` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `run-02` | `verifier-logs` | `0` | `false` | `ok` | `305bd96791d44cbb64d4046087a7a1d7486c9ffbca1b0d8130c5a8f3e9706975` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `run-02` | `compose-down` | `0` | `false` | `ok` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | `6aab1581d4baecea34ea70edf36fcd4f56b3b1c7c9792e8fa1551c2fe42e248b` |

## Failure scenarios

| Scenario | Stage | Exit code | Timed out | Diagnostic | Stdout SHA-256 | Stderr SHA-256 |
|---|---|---:|---|---|---|---|
| `missing-role` | `postgres-start` | `0` | `false` | `ok` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | `3baff0fc78ac557d268e6861c10c107d6ce79e5b5390024602abddddf52bf1f4` |
| `missing-role` | `V830-migrate` | `1` | `false` | `expected_failure` | `447a483e40984f12f2d82d60dc881a0e9001886963c116926dac08559a937eda` | `2ff6dcbadd33b2d269acbcb1b04e7dc4a510407b75ebac8b0c440f5b6d244f81` |
| `missing-role` | `compose-down` | `0` | `false` | `ok` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | `b707cbeabec97ec626e65f33b81a8c3d97759d4222ef7b47459be797bddcb4f0` |
| `extra-managed-table` | `postgres-start` | `0` | `false` | `ok` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | `7648a6202d05169b65e2e57a519aad1c37149aada9f244e4dc26470a958e9f45` |
| `extra-managed-table` | `V830-migrate` | `0` | `false` | `ok` | `c88d46afa900773703bec7c63a569bda1983a73fff408f44d401e0e5e796e6bb` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `extra-managed-table` | `mutation` | `0` | `false` | `ok` | `66541d51c1f7165aff7661955fa1c3a29fad69ac4c07515e27e18ac1fb4f7e76` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `extra-managed-table` | `V840-migrate` | `1` | `false` | `expected_failure` | `9f92f0433b0705a3cc4a76ffdbaba7a2a2a72a07f27d57721a364334859b80ff` | `80b7db4403c6b722f492aea0da15fe4994ef01c6763a52852e9a9a29593675cb` |
| `extra-managed-table` | `compose-down` | `0` | `false` | `ok` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | `c871f99b002e0c31aa827698cfd7c1095e24655b9fb9c9fd169626ec2e0931d2` |
| `forbidden-delete-grant` | `postgres-start` | `0` | `false` | `ok` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | `04036370c63c509a8740f904a984c44a357b5926f9155112b6da0721b6580ad0` |
| `forbidden-delete-grant` | `V830-migrate` | `0` | `false` | `ok` | `c88d46afa900773703bec7c63a569bda1983a73fff408f44d401e0e5e796e6bb` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `forbidden-delete-grant` | `mutation` | `0` | `false` | `ok` | `ac5edd8ccc256e797590beae03179d7b9951c07f5e759f67d15b2259b87c49ce` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `forbidden-delete-grant` | `V840-migrate` | `1` | `false` | `expected_failure` | `b1842b320dc7eb90ea2e34e99ba7df4537c97c16a1562b56f6d4a3eecc24d099` | `6d74b78d842f1f74dc29a5cfae7c1e8a921bba64a978bbd52e719a6181007911` |
| `forbidden-delete-grant` | `compose-down` | `0` | `false` | `ok` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | `acb1e2668d34baa469c0961dd88714bb7e2bb9115d89daa3a50b96cbc67cb709` |
| `missing-mutation-guard` | `postgres-start` | `0` | `false` | `ok` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | `0731a40009ace2443e09d0dc9b0d70e17081ad85bd93651ebff952cf23eef07a` |
| `missing-mutation-guard` | `V830-migrate` | `0` | `false` | `ok` | `63a9736912ab59a602e42d32463d56d7d4231f09b0663de6dd2b0df29e419d9f` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `missing-mutation-guard` | `mutation` | `0` | `false` | `ok` | `9fc3208c8d7878027e0563fcf5f9cf4799c11a43493363c52b45230c87cc4271` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `missing-mutation-guard` | `V840-migrate` | `1` | `false` | `expected_failure` | `16733e3857c3e56be855eb8f56e6858b9cb33444fe5a7cd264cf6eca5be2780b` | `62c68b8b2f8a41fff0bbe6916e867e7799b07549d76b7f83375c7a740a7db60e` |
| `missing-mutation-guard` | `compose-down` | `0` | `false` | `ok` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | `ac4978429d144963229a5b9d13aba829dbcb7be300e563f13c6f918bff12d481` |
| `checksum-mismatch` | `strict-validate` | `1` | `false` | `expected_failure` | `9960a7b53763e7951d4c1ccd0e7bd800c644255dc7762fa9d60a24856c78998d` | `37636460483d16f0955e5ac6681fcb8278ec1230ab911ad6e9b9969f44daf08c` |

## Toolchain

| Image | Locked tag | Locked digest |
|---|---|---|
| `postgres` | `18` | `sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280` |
| `redgate/flyway` | `13.4.0` | `sha256:c093a247b19ff09a6a72774569171ee355fff2ae44ceba4a4aa4b23235d99c93` |

- PostgreSQL version: `18.6`
- Flyway version: `13.4.0`

## Contract summary

- Verified: `true`
- Migrations: `19`
- Managed tables: `54`
- Managed schemas: `13`
- Physical foreign keys: `206`
- Mutation guards: `53`
- Contract SHA-256: `a9c53d0126b7997e0aac511d3a4baf1da02a5f10d829ca5113458be51813034a`
- Field contract SHA-256: `be79d991fa9e13e3f0af1c682333b6a063201387b78f7c9ec32a03bad51096ed`
