# Receipt and service contracts

## A working, versioned receipt boundary

`ReceiptCodec` encodes the actual booking shown in the app. **Export receipt** writes an immutable JSON snapshot to a narrow app-cache directory and opens the native Android share sheet with a temporary read grant. It does not send the file automatically. Profile fields and the idempotency key are excluded. The [JSON Schema](../contracts/receipt-v1.schema.json) describes the structural wire format; the golden fixture in `core/src/test/resources/receipt-v1.json` is exercised by Kotlin tests and can be reused by iOS and web consumers.

Version 1 emits ISO-8601 civil dates, explicit `USD`, and non-negative decimal strings for minor units. For compatibility, an omitted currency is interpreted as `USD`; any other currency fails validation. Clients must parse money with exact integer arithmetic, never floating-point currency. `simulated: true` is mandatory for this demo. Known status values are `confirmed` and `cancelled`; unknown statuses and versions fail closed, while additional object fields are ignored for additive evolution.

Invariants: `total = creditApplied + simulatedCardAmount`; `creditReturned` equals zero for a confirmed booking and equals `creditApplied` after cancellation. A cancelled receipt preserves the historical simulated card amount, but its status means that amount is void; no real money ever moved.

Tests cover the shared fixture, cancellation, omitted private fields, additive compatibility, incompatible versions/statuses, malformed amounts, unsupported currencies, inconsistent totals, and exact values beyond JavaScript's safe integer range.

## Proposed production service boundary

This section is a technical design for replacing the local authority, **not an implemented or deployed server**.

| Operation | Request | Successful behavior | Important errors |
| --- | --- | --- | --- |
| `POST /v1/quotes` | Stay, civil dates, guests, credit preference | Signed/versioned quote with expiry and available inventory | 422 invalid dates or occupancy; 409 inventory unavailable |
| `POST /v1/reservations` | Accepted quote ID, payment intent, `Idempotency-Key` | Durable reservation and receipt | 409 changed payload or expired quote; 402 decline |
| `GET /v1/reservations/by-request/{key}` | Authenticated request key | Reconcile a lost response against server truth | 404 absent request; 403 wrong account |
| `POST /v1/reservations/{id}/cancel` | Reservation and idempotency key | Refund original credit allocation exactly once | 409 cancellation window closed |
| `POST /v1/benefits/{id}/redeem` | Authenticated account context | Server-enforced one-time credit grant | 409 eligibility changed |
| `PATCH /v1/profile` | Allowed fields, revision/ETag | Apply validated changes with consent and audit history | 412 concurrent edit; 422 field validation |

Authentication and authorization derive account ownership from the server session, never a client-supplied balance or account ID. The server recomputes totals, performs inventory locking, authorizes a tokenized payment provider intent, and stores the request fingerprint plus response before acknowledging success. Payment side effects require provider idempotency and a durable state machine/outbox; a database transaction alone cannot make an external charge atomic.

Retry transport failures with bounded exponential backoff and jitter. Reuse the key and immutable payload while the outcome is unknown. A response timeout is not proof of failure: reconcile before creating a new intent. Do not retry validation, authorization, or decline responses automatically. Propagate coroutine cancellation without reclassifying it as a payment error.

Backend, Android, iOS, and web should share JSON fixtures and error-code definitions, contract-test old clients against new additive responses, and explicitly coordinate incompatible version changes. Locale affects rendering, not wire dates or arithmetic. The production identity flow would use a dedicated identity provider and consented verification service rather than collecting identity documents inside this demo.
