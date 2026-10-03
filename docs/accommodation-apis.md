# Accommodation comparison and API setup

Roam compares accommodation search results for a common destination, date range, guest configuration and currency. The first integration uses **SerpAPI's Google Hotels API**, with separate hotel and vacation-rental searches and an on-demand property view for booking-site offers. These are freshly retrieved search observations, not reservations, inventory holds, or guaranteed checkout quotes. External reservations belong to the selected booking provider; Roam does not charge a card or apply its wallet credits to them.

## Which account to create

Research checked October 2, 2026 against provider documentation. Availability and pricing can change.

| Provider | Fit for Roam | Recommendation |
| --- | --- | --- |
| [SerpAPI](https://serpapi.com/users/sign_up) | Hotel and vacation-rental discovery, property details and booking-site price links through Google Hotels results | **Create this account first.** This is the implemented adapter. The [current free plan](https://serpapi.com/pricing) includes 250 searches/month; the Starter plan lists 1,000 for $25/month. |
| [LiteAPI / Nuitée Connect](https://liteapi.travel/) | Direct hotel supply, room rates, prebooking and booking, with a free sandbox | Shortlist for a later in-app hotel booking integration. It is not a substitute for comparing every retail booking site's price, and it is not implemented in this milestone. |
| [Expedia Rapid](https://developers.expediagroup.com/rapid) | Contracted lodging supply and enabled Vrbo inventory | Consider after the comparison product is established. [Vrbo access](https://developers.expediagroup.com/rapid/lodging/vacation-rentals/vrbo-integration-guide) depends on the partner profile; it does not establish Airbnb coverage. |
| [Booking.com Demand](https://developers.booking.com/demand/docs/getting-started/try-out-the-api) | Search, availability and affiliate redirect for managed partners | A later partnership option. Confirm content and price-comparison permissions before implementing. The [published comparison rules](https://legacy.developers.booking.com/api/commercial/index.html?page_url=permitted-use&version=2.9) restrict reuse of Booking.com property content in comparison products. |
| [Expedia Travel Redirect](https://developers.expediagroup.com/travel-redirect-api/api/start-guide/getting-started) | Search with Expedia-group booking links | Its onboarding page currently says new API applications are paused. Do not depend on getting a new key now. |
| [Stay22 Direct Travel API](https://dev.stay22.com/docs/api) | Travel search/affiliate use cases | Its API sign-ups are currently paused while a replacement is built. Join its waitlist only if useful later. |

Airbnb's [API terms](https://www.airbnb.com/help/article/3418) limit access and use to the applicable approved program. No general Airbnb comparison feed is claimed or implemented. Roam offers a separate link to Airbnb's consumer site, without an Airbnb price card or assertion that Airbnb inventory was searched. Users must enter or confirm their trip on that site. Vacation rentals returned by the search provider are not relabeled as Airbnb listings.

## Put the key on the server

Create your SerpAPI account, obtain the API key from its dashboard, and set `SERPAPI_API_KEY` in the server's environment or secret manager. Never put it in `roam.properties`, an Android resource, a URL shipped to the phone, source control, or a support screenshot.

The comparison service can run independently of PostgreSQL, Supabase and Stripe:

```sh
./gradlew :server:installDist
ROAM_ENV=development server/build/install/server/bin/server --comparison-only
```

On Windows, use `gradlew.bat` and `server/build/install/server/bin/server.bat`. Shell launchers read environment variables; they do not automatically load a `.env` file. The comparison container configuration is described in the service runbook. Start in an isolated development environment; deploy public access behind HTTPS, edge rate limits and a provider spending limit.

In PowerShell, set `$env:ROAM_ENV = 'development'` before running the launcher. For containers, copy `server/.env.example` to the ignored `server/.env`, configure the comparison variables, then run `docker compose -f server/compose.comparison.yml up --build -d`. Search-only mode ignores the commerce credential slots. Production requires `ROAM_INGRESS_RATE_LIMITED=true` after configuring the proxy, and `ROAM_COMPARISON_PUBLIC_ACCESS=true` to permit a configured provider key on public search routes. `ROAM_COMPARISON_DAILY_REQUEST_LIMIT` defaults to 200 upstream calls per UTC day per process; account-wide controls must cover restarts and multiple replicas.

Without a provider key the service still starts and reports that search is not connected. It returns no fabricated prices. Tests use controlled provider responses and never consume your SerpAPI allowance.

## Configure Android

In the ignored root `roam.properties`, set only the public address of your service:

```properties
ROAM_COMPARISON_API_URL=http://10.0.2.2:8080
```

That local address is for an Android emulator and staging only. A hosted or physical-device endpoint needs HTTPS. If `ROAM_COMPARISON_API_URL` is omitted, it defaults to `ROAM_API_URL`.

Build `:app:assembleStaging` for local development. Staging opens Compare before sign-in; account and payment screens still require their own configuration. For an independent, unsigned comparison release, supply a public HTTPS comparison endpoint and build `:app:assembleComparison`. This variant installs as `com.roam.app.compare` and does not require Supabase or Stripe keys. Configure protected release signing before distribution.

The existing offline showcase also has a Compare entry. Its fictional stays and wallet remain separate from external search results. Missing search configuration never becomes simulated live availability.

## Price and coverage rules

* Searches use one room, an explicit adult count and child ages supported by this adapter. The interface states the room scope; larger parties needing multiple hotel rooms require a later extension.
* The same submitted trip is used for hotel and rental searches. Late responses from a previous trip cannot replace current results.
* A reported full-stay total takes priority over a nightly amount. A nightly rate is never multiplied to invent a total. Unknown totals or tax treatment remain unknown.
* Prices use exact decimal amounts in their declared currency. Roam does not invent exchange rates or rank mismatched currencies together.
* Provider details show available booking-site rates for that property. Room types, cancellation and meal plans may differ; a lower observed rate is not proof of an equivalent offer or the lowest price on the internet.
* Search and property views carry observation and expiry times. Refresh expired results before opening a priced offer. Rates can still change at the provider's checkout.
* Failed, timed-out, quota-limited and unconfigured sources are distinct from a successful search with no results. Successful sources remain visible when another source fails.
* This first adapter returns at most 50 properties per category and 60 property offers, without pagination. It does not claim exhaustive market coverage.

Search uses SerpAPI's `no_cache=true` option to request fresh results. Searching both categories normally makes two upstream calls; opening a property's offers makes another. Budget for refreshes and retries. Do not enable automatic paid renewals until traffic and abuse controls have been reviewed. Limits in a single service process do not replace a global account budget across replicas.

## Verify after signing up

Use your own staging key and inspect hotel and rental searches for several destinations, a child occupancy, JPY and a two-decimal currency. Compare the returned full-stay amounts and dates with the linked booking site, including mandatory charges, cancellation terms and currency. Exercise quota exhaustion, slow responses, provider errors, refresh, and leaving/returning to the app. Confirm that access logs never capture the outbound API key. No real provider response or booking has been verified without your account.

Technical references: [search parameters](https://serpapi.com/google-hotels-api), [property search responses](https://serpapi.com/google-hotels-properties), [property offer responses](https://serpapi.com/google-hotels-property-details), [LiteAPI rates](https://docs.liteapi.travel/reference/post_hotels-rates).
