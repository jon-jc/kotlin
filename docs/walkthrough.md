# A five-minute interview walkthrough

## 1. Lead with a working product

Open discovery. Search for Japan, save Kyoto, and show that the saved filter combines with the text and category filters. Resize to a tablet or switch dark mode to demonstrate that the native layout adapts. Explain that destination imagery is illustrative and all bookings are fictional.

## 2. Show value traveling with the account

Open Wallet. Claim the $25 welcome benefit and observe the balance rise from $85 to $110. The disabled claim action is only a presentation detail: the database transaction also enforces redemption exactly once, including concurrent calls.

## 3. Demonstrate the hard commerce case

Choose Kyoto for three nights, proceed to checkout, and leave credit enabled. Select **Demo controls → Response interrupted**, then confirm. The local transaction commits, but the interface simulates a lost acknowledgment. Choose **Recover my reservation**. One receipt appears and only one credit debit exists. Point to the unique request-key index, the service transaction, and the tests that run 24 retries at once.

Explain the distinction between a local atomic transaction and a real payment-provider side effect. Then show the implemented Ktor/PostgreSQL service: occupied-night uniqueness, immutable accepted quotes, deterministic Stripe keys, and leased payment/refund reconciliation. In configured staging, demonstrate the same recovery through Stripe test mode.

## 4. Close the loop

Cancel before check-in. Credits are returned exactly once, and the original allocation remains visible in the cancelled receipt. Export the receipt to inspect its versioned, privacy-minimized JSON. Show the golden contract test and explain why cents cross platforms as decimal strings.

## 5. Show what ownership looks like

Edit the passport and hide hometown from the public preview. Show CI, the milestone pull requests, native UI tests, and the benchmark traces. Discuss account isolation: each login owns a navigation entry, encrypted refresh credentials survive process recreation, and sign-out clears the entry and credential key. Show an actual review regression, such as a late refresh attempting to restore a signed-out session or a lost refund response recovered from a signed provider event.

Be precise about the evidence: this is an implemented portfolio product prepared for provider configuration, not a previously shipped service at consumer scale. Emulator performance validates the measurement pipeline. Real provider staging tests, physical-device measurements, accessibility service testing, operational controls and a monitored rollout remain necessary before launch.

## Useful code to discuss

* `core/CommerceService`: atomic invariants and idempotency.
* `data/RoomAccountStore`: serialized mutations and consistent observable snapshots.
* `app/RoamViewModel`: typed intent handling, in-flight protection, and saved checkout intent.
* `core/ReceiptCodec`: schema evolution and cross-platform money semantics.
* `data/CommerceIntegrationTest`: contention, rollback, cancellation, and disk reopen.
* `app/JourneyTest`: complete journeys against real Room on an Android emulator.
* `benchmark/RoamBenchmark`: reproducible cold-start and scroll measurement.
* `network/RemoteCommerceGateway`: server status reconciliation and accepted-price validation.
* `server/Commerce`: inventory, original credit allocation, provider recovery and worker fairness.
* `server/CommerceIntegrationTest`: real PostgreSQL contention and controlled provider failure scenarios.
