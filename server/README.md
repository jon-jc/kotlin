# Roam service

The service owns authenticated accounts, exact prices, expiring quotes, inventory, wallet entries, and reservation status. Supabase provides identity and PostgreSQL hosting; Stripe handles card details and payment processing. The Android app receives a PaymentIntent client secret and uses Stripe's native payment sheet. A client callback never confirms a reservation: only the service's verified Stripe state does.

The service builds and its integration suite runs without provider credentials. Running the connected product requires your Supabase project and Stripe keys. There is no unauthenticated development account, fake payment endpoint, or successful fallback when a provider is unconfigured.

## Verify without keys

From the repository root, with JDK 17 and Docker running:

```sh
docker compose -f server/compose.test.yml -p roam-integration up -d --wait
./gradlew :server:test :server:installDist
docker compose -f server/compose.test.yml -p roam-integration down
```

On Windows use `gradlew.bat`. The database binds only to loopback port 55432. Its credentials are deliberately public, test-only values, and its data lives in temporary memory. Never reuse this Compose file or its credentials for deployment. Tests fail if PostgreSQL is unavailable; they do not silently replace it with an in-memory database. Test payment adapters exist only in the test source set.

CI can supply `ROAM_TEST_DATABASE_URL`, `ROAM_TEST_DATABASE_USER`, and `ROAM_TEST_DATABASE_PASSWORD` to use its own PostgreSQL service. The default URL is `jdbc:postgresql://localhost:55432/roam_test`.

Tests cover account isolation, RS256/ES256 signatures and claims, malformed/oversized payloads, quote expiry, concurrent inventory conflicts, request-key reuse, lost payment/refund responses, provider idempotency expiry, cancellation and credit reversal, worker fairness, restart persistence, private profile revisions, live-payment rejection for fictional inventory, webhook signatures, and complete-response time/size limits.

## Configure managed services

1. Create a Supabase project. Enable email OTP with a configured mail provider and rate limits. In **Authentication → Email Templates → Magic Link**, replace the default link body with a code-based template containing `{{ .Token }}`, for example `<p>Your Roam sign-in code: {{ .Token }}</p>`. Roam asks users to type this code; it does not implement the default magic-link callback. Send and verify an actual email in staging to confirm the template and SMTP delivery. Select an asymmetric JWT signing key (ES256 or RS256). The service accepts only your configured issuer, audience `authenticated`, and authenticated non-anonymous users. Legacy HS256 tokens are deliberately unsupported. The app needs the project URL and **publishable** key. The service does not need a Supabase service-role key. [Supabase email OTP setup](https://supabase.com/docs/guides/auth/auth-email-passwordless).
2. Obtain the PostgreSQL JDBC connection information and CA certificate from the Supabase connection panel. Use `sslmode=verify-full`; mount the CA into the container and add `sslrootcert=/path/to/ca.pem` when the certificate is not already trusted. Prefer a direct or session-pool connection for this service. The configured database user must own the private `roam` schema for schema migration. Do not expose this schema in Supabase's Data API, grant access to the `anon`/`authenticated` database roles, or reuse this credential in Android.
3. Create Stripe **test** publishable and secret keys. The Android app receives the publishable key only. Copy the secret key and webhook signing secret into the service's environment. Register `https://YOUR_API/v1/webhooks/stripe` for `payment_intent.succeeded`, `payment_intent.canceled`, `payment_intent.payment_failed`, `payment_intent.processing`, `refund.created`, `refund.updated`, `refund.failed`, and `charge.refunded`. Configure the webhook API version as `2025-09-30.clover`, matching outbound requests.
4. Copy `.env.example` to `.env` inside this directory. Fill every required slot on the deployment host. The `.env` file and private keys are ignored by Git. Production startup fails if configuration is missing, provider mode is inconsistent, identity is not HTTPS, database TLS validation is disabled, or ingress rate limiting is not explicitly acknowledged.

| Environment variable | Purpose |
| --- | --- |
| `DATABASE_URL` | PostgreSQL JDBC URL; verified TLS required in production |
| `DATABASE_USER`, `DATABASE_PASSWORD` | Dedicated backend database credentials |
| `SUPABASE_URL` | HTTPS origin of your Supabase project |
| `STRIPE_SECRET_KEY` | `sk_test_...` initially; never bundled in Android |
| `STRIPE_WEBHOOK_SECRET` | Signing secret for this exact webhook endpoint |
| `ROAM_LIVE_PAYMENTS` | Defaults false; live mode additionally requires an `sk_live_...` key |
| `ROAM_INGRESS_RATE_LIMITED` | Set true after configuring the TLS proxy's limits |
| `ROAM_ENV` | `production`; use `development` only for local database TLS and explicit sample seeding |
| `PORT` | Internal HTTP port, default 8080 |

The service's private PostgreSQL schema is versioned with migration checksums and a transactional migration lock. A changed historical migration fails startup. Add a new migration version for future schema changes; never edit a migration after deploying it. Back up and validate migrations in staging before a production rollout.

## Build and run the container

```sh
./gradlew :server:installDist
docker compose -f server/compose.yml build
docker compose -f server/compose.yml up -d
```

The image runs as a non-root user with a read-only filesystem, removed capabilities, a memory limit, and a temporary `/tmp`. The provided Compose service binds HTTP only to localhost. Put it behind a TLS reverse proxy or use a managed container host with TLS termination. Do not expose port 8080 directly to the internet. Restrict ingress to the proxy and enforce HTTPS on the public endpoint. `/health/live` checks the process; `/health/ready` checks database access.

Configure request/body/header limits and per-client rate limits at that ingress, with a separate webhook budget and Stripe's retry behavior in mind. The service intentionally ignores forwarding headers and uses authenticated-user limits; trusting arbitrary `X-Forwarded-For` would permit bypasses. `ROAM_INGRESS_RATE_LIMITED=true` disables the local per-IP fallback so a reverse proxy's address does not group all customers under a single budget. It does not configure your proxy for you.

Pin container images to reviewed digests in the deployment pipeline. Scan the image and its dependencies and retain release artifacts. The supplied major-version base images receive patch updates at build time rather than embedding an unmaintained patch tag.

## Inventory and money

No production catalog or opening credit is created automatically. Real accounts start at zero. Activate only stays you actually have permission to sell; the service is the inventory authority for the single-unit-per-stay model. Selling the same property through another channel requires a channel manager/inventory integration before launch.

For an isolated development environment, after setting the database variables and `ROAM_ENV=development`, run:

```sh
./gradlew :server:run --args="--seed-demo"
```

This explicit command imports the existing three fictional stays with `demo=true`, without adding account credit. Live Stripe mode refuses to quote or charge for these records. Real inventory must be curated into `roam.stays` with an appropriate `ApiStay` payload, validated IANA property time zone, exact USD nightly minor units, and `demo=false`; it starts inactive unless explicitly activated. There is no public administrative write API. Access is through restricted database/operator tooling.

Quotes last five minutes and are immutable. They do not hold rooms. Accepting a quote atomically reserves each occupied date in `[checkIn, checkOut)` and allocates the original accepted wallet amount. Concurrent reservations cannot occupy the same night. A changed credit balance produces `QUOTE_STALE` rather than increasing the accepted card amount. Prices use exact integer cents; an 8% service fee is rounded half up. If applying credit would leave less than Stripe's 50-cent USD minimum, the quote reduces the applied credit to leave a 50-cent charge.

Cancellation is available before the property's local check-in date. A payment awaiting completion holds inventory for 30 minutes. The worker cancels the provider intent before releasing inventory and credits. If the payment completed in the meantime, the service refunds it before finalizing cancellation. A failed provider call leaves the reservation held and retryable, never silently refunded or sold twice.

## Recovery and operations

The reservation table is the durable work queue. Workers claim due records with PostgreSQL locks, persist leases, and back off retries; one old unresolved request cannot permanently starve newer work. A restarted or second service instance can continue from the same database. Stripe operations use a deterministic key derived from the server reservation ID.

Stripe can discard idempotency records after 24 hours. The service stops recreating an unknown payment/refund operation after 23 hours and marks it for support. Signed provider events can recover known intent/refund IDs after that window. Unsolicited or partial Dashboard refunds also raise `requiresSupport` instead of claiming the reservation was cancelled. Review-required reservations retain inventory and are excluded from automatic recreation.

Monitor logs for reconciliation failures and query this operator view through restricted database access:

```sql
SELECT id, state, payment_intent_id, refund_id, review_reason, created_at
FROM roam.reservations
WHERE review_required
ORDER BY created_at;
```

Resolve uncertain operations against the Stripe Dashboard/API using the immutable reservation ID in metadata. Verify payment/refund ownership, amount, currency, and mode before attaching a missing provider ID. After a documented operator review, clear the review flag and set `next_reconcile_at=now()` to let normal reconciliation finish. Never release reserved nights or alter a wallet balance to bypass an unresolved payment. A customer-support workflow, alert routing, and a named operator are required before accepting live payments.

Use short-lived Supabase access tokens. The public-key cache lasts up to five minutes in this service, in addition to provider caching; rotate standby keys before activation and restart the service to discard its cache during urgent revocation. This API does not provide immediate per-session JWT revocation independent of expiry.

No authorization headers, profiles, client secrets, raw provider bodies, or exception messages are written to application logs. Keep infrastructure access logs equally restrained. Configure retention, encrypted backups, and a tested restore for the PostgreSQL service. Keep completed idempotency records for at least the associated booking lifetime. Delete expired unused quotes according to a documented retention policy; never delete a quote referenced by a reservation. Account history responses currently return the newest 200 reservations and 200 ledger entries; a cursor-based history interface is needed before supporting larger account histories.

## Launch requirements

This repository provides a runnable service and client integration, not configured merchant infrastructure. Before switching both Stripe mode and keys to live, verify your own catalog, end-to-end test payments and refunds, webhook delivery, SMTP/OTP delivery, HTTPS ingress and rate limits, backup restoration, service alerts, support escalation, privacy/export/deletion procedures, legal commercial policies, and Android signing/distribution. Access tokens authenticate accounts; profile edits do not verify a person's identity. Taxes, supplier payouts, chargebacks, fraud review, multi-unit inventory, and cross-channel availability require the relevant integrations and operational policies for your business.

References: [Supabase JWT validation](https://supabase.com/docs/guides/auth/jwts), [Supabase signing-key rotation](https://supabase.com/docs/guides/auth/signing-keys), [Stripe idempotent requests](https://docs.stripe.com/api/idempotent_requests), [Stripe webhook signatures](https://docs.stripe.com/webhooks/signature), [PostgreSQL JDBC TLS](https://jdbc.postgresql.org/documentation/ssl/).
