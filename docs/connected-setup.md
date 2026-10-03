# Connected setup

Roam uses a native Kotlin/Compose client, a portable Kotlin/Ktor service, Supabase Auth and PostgreSQL, and Stripe. Supabase combines managed identity and a standard PostgreSQL database; the service keeps commerce authority independent of the client and hosting provider. The container can run on a VM or managed container platform. No paid resources are created automatically.

## Keys and account setup

1. Create a Supabase project with an asymmetric ES256 or RS256 JWT signing key. Configure an email delivery provider. In the **Magic Link** email template, include `{{ .Token }}` so the email contains the numeric code Roam accepts. The application currently supports email codes, not magic-link deep links. Configure sign-up/email rate limits in Supabase.
2. Create a Stripe test environment. Put its `sk_test_...` key and endpoint-specific `whsec_...` webhook secret in the server environment. Use the matching `pk_test_...` publishable key in Android. Register the events and API version documented in the [service runbook](../server/README.md).
3. Give the service a dedicated PostgreSQL credential. The private `roam` schema must not be exposed through Supabase's public Data API. In production, verify the database certificate with `sslmode=verify-full` and the appropriate CA.
4. Copy `server/.env.example` to `server/.env`, supply the values, and follow the service runbook. The example is production-oriented; local development explicitly sets `ROAM_ENV=development`. Database and Stripe secret values belong only on the service host.
5. Copy `roam.properties.example` to `roam.properties` at the repository root. Fill `ROAM_API_URL`, `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, and `STRIPE_PUBLISHABLE_KEY`. Set the optional `ROAM_COMPARISON_API_URL` for a separate search service, or remove that property to use `ROAM_API_URL` for both. The copied example includes an emulator comparison address: replace it with your hosted HTTPS address or remove it when deploying hosted services. Environment variables with these names override the file. All five settings are public client configuration embedded in the APK. Supabase `sb_secret_...`, service-role JWTs, and Stripe `sk_...` keys are never accepted as client configuration.

The connected client requires Supabase's current `sb_publishable_...` key. Do not substitute an older anonymous JWT or a privileged server key. Both local files are ignored by Git; the committed examples contain placeholders only.

## Local server and emulator

With the database configured, run `./gradlew :server:installDist`. Use `docker compose -f server/compose.yml up --build -d` to start the service, then check `/health/ready`. The sample Compose deployment binds to host loopback. A local Android emulator reaches the host through `http://10.0.2.2:8080`. Set that as `ROAM_API_URL` in the staging configuration. Use HTTPS for hosted services; the staging network policy permits cleartext only for the explicit local development hosts.

Build with `./gradlew :app:assembleStaging` and install `app/build/outputs/apk/staging/app-staging.apk`. Windows uses `gradlew.bat`. The app installs as `com.roam.app.staging`, leaving the offline demo's data separate. Staging opens Compare before sign-in; choose **Your passport** to reach account and payment features. Missing or invalid commerce settings show a setup-unavailable screen there. Comparison can use its own configured service independently.

Seed fictional inventory only in development with `./gradlew :server:run --args="--seed-demo"` after setting the database environment variables. This requires `ROAM_ENV=development`; live Stripe mode rejects these sample stays. Use Stripe test cards in staging. New users start with no wallet credit and no invented identity verification or membership benefits.

For a remote physical device, use a properly secured HTTPS staging endpoint. Local-only HTTP exceptions are absent from the release manifest.

## Hosting choice

Use **Google Cloud Run** for the container, with Supabase for PostgreSQL and sign-in. Configure instance-based billing and at least one minimum instance: Roam's reconciliation worker must have CPU between requests. Start with 1 CPU, 512 MiB memory, request concurrency 20, and a maximum of three instances; tune these initial limits with load tests. The service opens at most six database connections per instance, so reserve capacity for at least 18 plus migrations, overlap during rollout and operator access.

Keep the API behind an HTTPS load balancer with edge request/body limits and rate limiting. Restrict service ingress to that path, and only then set `ROAM_INGRESS_RATE_LIMITED=true`. Use `/health/live` for liveness and `/health/ready` for startup/deployment readiness. Store backend credentials in Secret Manager with version-pinned references; mount the PostgreSQL CA read-only and set `sslrootcert` in the JDBC URL. Give the runtime identity access only to its required secrets.

Cloud Run may terminate idle instances; durable leases let another instance continue. Configure alerts for failed readiness, reconciliation retries and review-required reservations, and exercise restore/rollback in staging. These are deployment settings to apply after creating your cloud project; this repository has not provisioned cloud infrastructure. See [Cloud Run CPU allocation](https://docs.cloud.google.com/run/docs/configuring/billing-settings), [minimum instances](https://docs.cloud.google.com/run/docs/configuring/min-instances), and [secret references](https://docs.cloud.google.com/run/docs/configuring/services/secrets).

## Verify the connected flow with your keys

Sign in using a delivered email code, edit the profile and privacy settings, save a stay, review a quote, pay with a Stripe test card, and wait for a server-confirmed reservation. Test an authentication-required card and a decline. Interrupt the connection after confirmation, reopen Trips, and resume the pending reservation: it retains the original request key. Cancel before the property's check-in date and verify the refund in Stripe and the final reservation state in Roam. Exercise webhook redelivery and a service restart.

Pending payments, pending cancellations, failures, and review-required reservations have explicit states. The UI preserves the original accepted amounts and booking reference. It never interprets an interrupted request or a payment-sheet callback as proof of completion. Server review flags require an operator to resolve uncertain provider operations.

Automated tests use controlled provider adapters and signed test JWTs; they do not prove your real email delivery, Stripe account configuration, webhook routing, or cloud deployment. No real provider credentials are needed to run those tests.

## Build variants and release

| Variant | Services | Purpose |
| --- | --- | --- |
| `debug` | Local demo | Development and native demo tests |
| `benchmark` | Local demo | Optimized installable showcase and measurements |
| `staging` | Comparison and connected commerce | Opens Compare before sign-in; your test services; separate application ID |
| `release` | Comparison and connected commerce | HTTPS and live publishable key validation; unsigned by default |
| `comparison` | Comparison only | Public HTTPS search endpoint; no Supabase or Stripe configuration; unsigned; installs as `com.roam.app.compare` |

Run `:app:validateReleaseConfiguration` to check the public production settings. Release builds depend on this check, including HTTPS validation for a configured comparison endpoint. The independent comparison variant uses `:app:validateComparisonReleaseConfiguration`; see [comparison setup](accommodation-apis.md#configure-android). Add signing in your protected CI environment; no signing credential is included. Back up signing keys and preserve the application ID when distributing updates.

The [service runbook](../server/README.md) covers provider keys, containers, database migration, deployment limits, and operational recovery. Finish its launch requirements before accepting real money. Add an appropriate privacy policy, support channel, account deletion/export workflow, and store disclosures for the actual business and data practices.

References: [Supabase email codes](https://supabase.com/docs/guides/auth/auth-email-passwordless), [Supabase signing keys](https://supabase.com/docs/guides/auth/signing-keys), [Stripe Android SDK](https://github.com/stripe/stripe-android).
