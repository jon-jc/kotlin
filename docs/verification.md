# Verification evidence

## Connected foundation — v2.0.0

Verified on October 2, 2026. [PR #4](https://github.com/jon-jc/kotlin/pull/4) passed Android compilation/lint, JVM tests, native device tests, PostgreSQL integration and container smoke checks before merging. Provider configuration and commercial launch remain separate from these controlled tests.

| Boundary | Passing tests | Evidence |
| --- | ---: | --- |
| Core | 20 | Checked money, date/occupancy policy, historical stay snapshots, receipt privacy and unresolved-state rejection |
| Network | 27 | Concurrent token rotation, logout races, wrong-account responses, changed quotes, bounded/cancellable HTTP, payment reconciliation |
| PostgreSQL service | 26 | Signed JWTs, cross-account access, overlapping inventory, stale prices, original credit allocation, lost Stripe responses, refunds, worker fairness and shutdown |
| Room/SQLite | 18 | Real transactions, concurrent retries, rollback, persisted reopen and v1-to-v2 on-disk migration |
| Android state | 28 | Draft identity, quote races, pending recovery, profile revision conflicts, auth and payment lifecycle |
| API 35 device | 25 | Complete journeys, email-code UI, session navigation isolation, payment-state presentation, Keystore ciphertext/tamper/logout tests |

**119 JVM tests and 25 native tests passed locally; no tests were skipped.** The API 35 x86_64 emulator run completed in 25.3 seconds after installation. Its suites use isolated state and controlled authentication; no email was sent and no real card was charged. GitHub also passed all native tests independently.

The JSON Schema check accepts golden/additive receipts and rejects five incompatible forms. Debug, staging and optimized benchmark APKs compile. Debug/staging/release lint has zero errors; dependency-update warnings remain visible rather than suppressed. Connected release shrinking also compiles with synthetic public configuration targeting `.invalid` domains; that unsigned verification artifact is not distributed as a working production app.

Five release configuration checks reject missing settings, a misplaced Stripe private key, a misplaced Supabase private key, a test key in a production release, and a cleartext production endpoint. These checks run in CI; public configuration is distinct from server secrets.

## Actual runtime checks

The Linux service image builds from the installed JVM distribution. It starts as the unprivileged `roam` user with a read-only root filesystem, temporary `/tmp`, all capabilities dropped, and privilege escalation disabled. Live/readiness endpoints return success; explicit development seeding returns three fictional stays; unauthenticated account access returns 401 with `Cache-Control: no-store`. CI repeats this against an isolated PostgreSQL database. The backend test suite requires real PostgreSQL and fails when it is unavailable.

The unconfigured staging APK was installed and inspected on the emulator. It displays the connection-unavailable screen and never falls back to a simulated account. The offline optimized APK was installed and successfully completed startup and scrolling measurements.

## Performance evidence

Two Macrobenchmark methods completed: five cold-start iterations and three discovery-scroll iterations, using `CompilationMode.None()` on the minified demo. API 35 x86_64, software graphics and uncontrolled host load make these diagnostic measurements rather than physical-device release gates.

| v2 diagnostic measurement | Result |
| --- | ---: |
| Initial display, median | 536.9 ms |
| Fully drawn, median | 873.9 ms |
| Scroll CPU frame duration, P50 | 19.0 ms |
| Scroll CPU frame duration, P95 | 35.2 ms |
| Scroll deadline overrun, P99 | 47.4 ms |

[Raw v2 JSON](performance/emulator-api35-v2.json) contains all samples; the release archive contains all eight Perfetto traces. Frame CPU and tail-overrun values are worse than the previously recorded v1 run. This is not evidence of a speedup or a controlled regression estimate: physical-device profiling is required, including the connected flow under network loss and provider latency. An app-specific baseline profile, memory/load tests and a broader device matrix remain work before a commercial release.

The [v1 report](verification-v1.md) preserves the earlier image-decoding investigation, raw before/after measurements, screenshots and trace queries. Those earlier figures are not presented as current connected-product performance.

## Reproduce

```sh
docker compose -f server/compose.test.yml -p roam-integration up -d --wait
./gradlew spotlessCheck :core:test :network:test :server:test :data:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:lintStaging :app:assembleDebug :app:assembleStaging :app:assembleBenchmark :benchmark:assembleBenchmark
./gradlew :app:connectedDebugAndroidTest
python tools/check_release_guards.py
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

Use a physical device for the final command. An emulator diagnostic run requires `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`. Windows uses `gradlew.bat`. The shared receipt schema check additionally requires `jsonschema==4.25.1` and runs through `python tools/check_contract.py`.

## What this does not establish

Real Supabase email delivery, Stripe authentication challenges/charges/refunds, webhook routing, cloud uptime, backup restoration, production signing and store distribution require your configured services and staging verification. Test payment adapters are confined to test source sets. Commercial inventory authorization, taxes/payouts, disputes, support operations, privacy/export/deletion procedures, localization and comprehensive assistive-technology testing are not inferred from passing tests. Follow the [setup guide](connected-setup.md) and [service launch requirements](../server/README.md#launch-requirements).
