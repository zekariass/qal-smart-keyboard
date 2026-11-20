# ADR 0045 — Payments core: country resolution, provider routing, ArifPay as the Ethiopian default

**Status:** Accepted (step A1 of the payments work) · **Date:** 2026-11-20 · **Requested by:** product owner ·
**Plan steps:** M4.1 (billing core), M4.6 (market/channel policy engine), the ArifPay part of M4.9 and the M4.2 shape ·
**Spec:** SD §15 (entitlements), §16 (billing: 16.1 principles, 16.3 eligibility rules 1–7, 16.4 Ethiopia, 16.5
renewal, 16.6 orders, 16.9 webhooks), §19 (privacy); plan §0.1 N2, N6, N7, N12; V1 billing tables; contract Billing,
Webhooks and Admin tags · **Builds on:** ADR 0011 (metering), 0013 (entitlement token), 0014 (provider SPI pattern),
0019 (interim admin access) · **Amends (needs SD/plan amendment, see "Deviations"):** SD §16.4, §16.7, §16.8; plan M4.9.

## Context
The owner wants the Ethiopian local flow live first, with **ArifPay as the default provider**, Chapa as the
alternative, and a global option (RevenueCat, store-managed) for everyone else, without hard-coding countries, and
with the backend deciding the billing country from several signals. Later steps plug in without core changes:
**A2** = Chapa adapter, RevenueCat webhooks/store purchases, provider-vs-ledger reconciliation; **B** = Android paywall,
WebView checkout, RevenueCat SDK.

## Architecture

```
 app (play/direct build) ── X-Client-Platform, salesChannel/distributionChannel, X-Qal-Network/Sim-Country
        │                    (edge adds CF-IPCountry [+ X-Qal-Edge-Secret])
        ▼
 platform.geo.CountryResolver ──► CountryResolution {country, source, confidence, flags}  (no IP stored/logged)
        │
        ▼
 billing.ProviderRouting  (market = country if confidence ≥ min, else ZZ)
   billing_provider_routes (country|ZZ × platform × channel → ordered providers)
   billing_market_channel_policies (fail-closed gate, purchase flow)
   payment_providers / payment_provider_accounts / provider_payment_methods / payment_provider_account_methods
   ProviderRegistry (adapters by provider_code: PaymentProvider gateways, StoreBillingProvider stores)
        │ ordered options (first = default)
        ▼
 OrderService ── payment_orders (server price, snapshot, idempotency, resolved country + signal summary)
 AttemptService ── payment_attempts (INITIATED committed first) ──► PaymentProvider.initiateCheckout ──► checkoutUrl
        │
   ┌────┴───────────────── triggers, none of which changes payment state ─────────────────┐
   │ webhook /billing/webhooks/{provider}/{account} → payment_webhook_events → outbox      │
   │ return  /billing/return/{attemptId}            → outbox (≤ 1 per 30 s)                │
   │ reconciliation task (db-scheduler)             → stuck open attempts                  │
   └────┬───────────────────────────────────────────────────────────────────────────────────┘
        ▼
 PaymentVerifier: PaymentProvider.verify (server to server) → match reference, nonce, amount, currency
        │ one DB transaction
        ▼
 attempt SUCCEEDED · payment_transactions CAPTURE · order PAID · Fulfilment (user_subscriptions MANUAL_RENEW term,
 subscription_payment_allocations, subscription_events, payment_receipts) · outbox billing.subscription.changed
        ▼
 entitlements: OutboxEventHandler invalidates the cached resolution → /me/entitlements and the token show the plan
```

Code: `platform.geo` (named interface `geo`), `billing` (core, package-private), `billing.spi` (named interface `spi`),
`billing.arifpay` (adapter). Migration `V25__payments_provider_routing.sql`.

## Country detection (requirement 1)
`CountryResolver.resolve(request)`; the backend is the final authority.

| Signal | Rule |
|---|---|
| `CF-IPCountry` (primary) | Trusted only when the request came through the configured edge: direct peer (`getRemoteAddr()`) inside `qal.geo.trusted-proxies` (CIDRs, IPv4/IPv6) **and/or** header `X-Qal-Edge-Secret` equal to `qal.geo.edge-secret` (constant-time). Every configured check must pass, at least one must be configured. **Default: never trusted** (local dev, unknown edges). An untrusted header is ignored and flagged `IP_UNTRUSTED`. |
| `XX`, absent from a trusted edge, malformed | No IP country, `IP_UNKNOWN`. |
| `T1` (Tor) | No IP country, `ANONYMIZER`. |
| `X-Qal-Network-Country`, `X-Qal-Sim-Country` | Android `networkCountryIso` / `simCountryIso`; trimmed, upper-cased, accepted only if ISO 3166-1 alpha-2 (`Locale.getISOCountries()`), else ignored with `INVALID_HINT`. |
| `DEV_MOCK_COUNTRY` (`qal.geo.dev-mock-country`) | Replaces the IP country (source `DEV_MOCK`, HIGH). **The prod/production/staging profiles refuse to start** with it set (a warning is not enough: it would let every caller buy as that country). |

Decision: trusted IP country → source `IP_EDGE`, confidence HIGH if a hint agrees, MEDIUM without hints, LOW if every
hint disagrees. No IP country → the hints decide **only when network and SIM agree** (source `CLIENT_HINTS`, LOW);
otherwise unknown (NONE). Flags: `IP_SIM_MISMATCH`, `IP_NETWORK_MISMATCH`, `NETWORK_SIM_MISMATCH`, and
`POSSIBLE_VPN_OR_PROXY` only when the IP country differs from network **and** SIM which agree with each other (a single
mismatch is travel/roaming as often as anything, never "VPN").

**Safe policy for eligibility:** `qal.billing.min-country-confidence` (default **MEDIUM** = a trusted IP country). Below
it the market is `ZZ` (global default), so **client hints alone never unlock a country-specific provider**. An operator
may lower it to LOW deliberately. Logged: country, source, confidence, flags — never an IP. Stored on the order:
`payment_orders.metadata.countryResolution` (country, source, confidence, ipCountry, ipTrusted, hint countries, flags;
no address). **Existing entitlements never depend on the current country**: the resolver is used for options and new
orders only; a term bought in Ethiopia keeps working abroad.

Deployment: put Cloudflare's published ranges (or the load balancer's private range) in `QAL_GEO_TRUSTED_PROXIES`
and/or have the edge add `X-Qal-Edge-Secret` (Transform Rule) with `QAL_GEO_EDGE_SECRET` (≥ 24 chars in prod). Do not
enable `server.forward-headers-strategy` without re-checking this: it rewrites `getRemoteAddr()`.

## Provider resolution (requirement 2)
`ProviderRouting.decide(country, confidence, channel[, currency, amount])`:

1. **Market** = country when confidence ≥ minimum, else `ZZ`.
2. **Routes** = `billing_provider_routes` of (market, platform, channel); none → the `ZZ` routes.
3. **Policy** (rule 6, fail closed) = the market's own `billing_market_channel_policies` row; only when the routes came
   from `ZZ` and the market has no row, the `ZZ` row. Must be APPROVED, in its validity window, `purchase_ui_allowed`
   and not `NONE`/`CONSUMPTION_ONLY`. A country with its own routes never borrows the global policy.
4. **Each route by priority** is offered when: enabled; provider `is_active` + `ACTIVE` (rule 1); adapter registered for
   `qal.billing.environment` (rule 2); type fits the flow (`APP_STORE` ⇔ `STORE_BILLING`; gateways ⇔
   `LOCAL_GATEWAY_IN_APP`/`EXTERNAL_WEB_CHECKOUT` with `external_payment_allowed`); gateways need ≥ 1 account method
   passing rules 3–5 (account of the market/currency/environment active, base and account method enabled+AVAILABLE,
   amount within both ranges). Rule 7 (recurring) is not offered (MANUAL_RENEW only).
5. The first offered option is the **default** (`isDefault`, `priority` in `PaymentMethodOption`). Skipped routes are
   reported with a reason (`PROVIDER_INACTIVE`, `NO_ADAPTER`, `POLICY_REVIEW_REQUIRED`, …) in the admin view and logs.

Channel = `X-Client-Platform` + the build's distribution channel, carried as `distributionChannel` (options) /
`salesChannel` (orders): Android `play` → `GOOGLE_PLAY`/`ANDROID_GOOGLE_PLAY`, `direct` → `DIRECT_DOWNLOAD`/
`ANDROID_DIRECT`; a present `X-Client-Platform` must match. The channel is client-asserted; it only selects which
approved policy applies (hardening with Play Integrity installer data: OI-PAY-10).

### Seed configuration (V25; everything OFF until onboarding)
| Country | Platform | Channel | Providers (priority) | Policy seed |
|---|---|---|---|---|
| ET | ANDROID | DIRECT_DOWNLOAD | ARIFPAY (1, default), CHAPA (2) | LOCAL_GATEWAY_IN_APP, REVIEW_REQUIRED |
| ET | ANDROID | GOOGLE_PLAY | ARIFPAY (1, default), CHAPA (2) | LOCAL_GATEWAY_IN_APP, REVIEW_REQUIRED — allowed while Play billing is unavailable in ET (see below) |
| ZZ | ANDROID | GOOGLE_PLAY | REVENUECAT (1) | STORE_BILLING, REVIEW_REQUIRED |
| ZZ | ANDROID | DIRECT_DOWNLOAD | REVENUECAT (1) | STORE_BILLING, REVIEW_REQUIRED (A2 decides the flow for a sideloaded APK) |
| ZZ | IOS | APPLE_APP_STORE | REVENUECAT (1) | STORE_BILLING, REVIEW_REQUIRED |

Also seeded: provider `REVENUECAT` (APP_STORE, CONFIGURATION_REQUIRED, inactive; adapter in A2), ArifPay method
`ARIFPAY_CHECKOUT` (ET/ETB, new method type `HOSTED_CHECKOUT`: the ArifPay page lets the payer choose telebirr, CBE,
banks, cards …), accounts `arifpay-et-sandbox` / `arifpay-et-production` (secret references `env:` names only) with their
account-method links, all UNVERIFIED/inactive; CHAPA stays CONFIGURATION_REQUIRED (no adapter until A2, so it is
skipped); provisional ETB prices (PLAN_PREMIUM 299.00/30 days, 2 990.00/year; PLAN_PREMIUM_PLUS 599.00/5 990.00;
INCLUSIVE; `metadata.provisional`, OI-05/OI-10) for `ANDROID_DIRECT` and `ANDROID_GOOGLE_PLAY`; products stay DRAFT.
New tables: `billing_provider_routes`, `billing_receipt_sequences`.

**ET + Play build.** Google's Payments policy FAQ
(https://support.google.com/googleplay/android-developer/answer/10281818): where Google Play's billing system is not
available, the requirement to use it does not apply; the linked country list
(https://support.google.com/googleplay/android-developer/answer/12201481) includes Ethiopia, and
https://support.google.com/googleplay/android-developer/answer/9306917 shows Ethiopia without merchant registration.
So ET/play → ArifPay (Chapa alternative) is **allowed while Play billing is unavailable in Ethiopia** — not a release
blocker. **Monitored condition (OI-PAY-6):** when Google announces Play billing for Ethiopia, switch ET/play to
RevenueCat (SQL below). The seeded policy row still needs a named reviewer like every other policy.

### Go-live switches (SQL; an admin UI for policies comes later)
```sql
-- merchant onboarding done (OI-09 / OI-PAY-*): provider, account, methods
UPDATE payment_providers SET provider_status = 'ACTIVE', is_active = TRUE WHERE provider_code = 'ARIFPAY';
UPDATE payment_provider_accounts SET account_status = 'ACTIVE', is_active = TRUE WHERE account_code = 'arifpay-et-production';
UPDATE provider_payment_methods SET availability_status = 'AVAILABLE', is_enabled = TRUE, last_verified_at = NOW()
 WHERE method_code = 'ARIFPAY_CHECKOUT';
UPDATE payment_provider_account_methods SET availability_status = 'AVAILABLE', is_enabled = TRUE, last_verified_at = NOW()
 WHERE provider_account_id = (SELECT id FROM payment_provider_accounts WHERE account_code = 'arifpay-et-production');
-- finance: final prices (OI-05, OI-10), then sell the plans
UPDATE billing_products SET product_status = 'ACTIVE' WHERE product_code IN ('PLAN_PREMIUM', 'PLAN_PREMIUM_PLUS');
-- POLICY_REVIEWER (named, four-eyes): approve the market/channel policy
UPDATE billing_market_channel_policies
   SET approval_status = 'APPROVED', reviewed_at = NOW(), reviewed_by_user_id = '<reviewer users.id>',
       purchase_ui_allowed = TRUE, external_payment_allowed = TRUE
 WHERE country_code = 'ET' AND client_platform = 'ANDROID' AND distribution_channel IN ('DIRECT_DOWNLOAD', 'GOOGLE_PLAY');
```
Runtime: `QAL_BILLING_ENVIRONMENT=PRODUCTION`, `QAL_ARIFPAY_ENVIRONMENT=PRODUCTION`, `ARIFPAY_SECRET_KEY`,
`QAL_ARIFPAY_WEBHOOK_SECRET` (≥ 32 chars), `QAL_PUBLIC_BASE_URL` (https), edge trust (above).

### Switching providers (config/data only)
- **Admin API** (FINANCE / SUPER_ADMIN, permission `providers:configure`; read: `billing:read`; audited
  `BILLING_PROVIDER_ROUTES_REPLACE`): `GET /admin/v1/billing/provider-routes/ET/ANDROID/DIRECT_DOWNLOAD` → `revision`, then
  `PUT` the same path with `{"expectedRevision": <rev>, "routes": [{"providerCode": "CHAPA"}, {"providerCode": "ARIFPAY"}]}`
  (list order = priority; stale revision → 409 `REVISION_CONFLICT` with `currentRevision`; empty list removes the scope
  so the country falls back to `ZZ`). Do it for each channel. Chapa must also be active with an adapter (A2), otherwise
  ArifPay stays the effective default at priority 2.
- **SQL equivalent** (one transaction; the priority constraint is deferrable):
  ```sql
  BEGIN;
  UPDATE billing_provider_routes r SET priority = CASE p.provider_code WHEN 'CHAPA' THEN 1 ELSE 2 END,
         revision = r.revision + 1, updated_at = NOW()
    FROM payment_providers p
   WHERE p.id = r.provider_id AND r.country_code = 'ET' AND r.client_platform = 'ANDROID';
  COMMIT;
  ```
- **ET/play → RevenueCat** (monitored condition OI-PAY-6) — routes and flow change together:
  ```sql
  BEGIN;
  DELETE FROM billing_provider_routes WHERE country_code = 'ET' AND client_platform = 'ANDROID' AND distribution_channel = 'GOOGLE_PLAY';
  -- now ET/play uses the ZZ routes (REVENUECAT); give ET its own STORE_BILLING policy:
  UPDATE billing_market_channel_policies SET purchase_flow = 'STORE_BILLING', external_payment_allowed = FALSE,
         reviewed_at = NOW(), reviewed_by_user_id = '<reviewer>', updated_at = NOW()
   WHERE country_code = 'ET' AND client_platform = 'ANDROID' AND distribution_channel = 'GOOGLE_PLAY';
  COMMIT;
  ```
  (or the admin `PUT` with `[{"providerCode":"REVENUECAT"}]`, plus the policy update). Active ArifPay terms keep running
  to their end (entitlements never depend on the provider).

## Provider SPI (requirement 3)
`billing.spi.PaymentProvider`: `providerCode()`, `supports(environment)`, `initiateCheckout(CheckoutRequest)` →
`CheckoutSession{providerReference, checkoutUrl, expiresAt}`, `verify(VerificationRequest)` →
`VerificationResult{status, providerReference, providerTransactionReference, amount, merchantReference,
providerMethodCode, occurredAt, failureCode}`, `authenticate(WebhookRequest)` → `VALID|INVALID|NOT_APPLICABLE`,
`parseWebhook` → `WebhookEvent` (redacted payload), optional `discoverMethods` (Chapa, A2). Statuses map to
`ProviderPaymentStatus` (SUCCEEDED, PENDING, NOT_STARTED, FAILED, CANCELLED, EXPIRED, UNKNOWN); failures are
`PaymentProviderException{REJECTED|UNAVAILABLE|INVALID_RESPONSE|MISCONFIGURED, safeCode}`. Adapters own their secrets;
the core decides matches and state. `StoreBillingProvider` (A2: RevenueCat) only needs `providerCode`/`supports` today.

## ArifPay adapter (verified against the live sandbox, 2026-11-20)
Docs: https://developer.arifpay.net/docs (+ its Postman collections). A live opt-in smoke test
(`ArifPayLiveSandboxIT`) created a real sandbox session, verified it NOT_STARTED, paid it through the sandbox simulator
and verified SUCCEEDED.

- **Auth:** header `x-arifpay-key` (env `ARIFPAY_SECRET_KEY`, never logged/returned). A **sandbox key works only on
  `/api/sandbox/...` routes**: `POST /api/checkout/session` with it answers `400 "Test API Key found"`. Base URLs:
  sandbox `https://gateway.arifpay.org` (verified), production `https://gateway.arifpay.net` (from the production Postman
  collection; confirm, OI-PAY-1). Routes follow `QAL_ARIFPAY_ENVIRONMENT`; each is overridable.
- **Create:** `POST /api/sandbox/checkout/session` (prod `/api/checkout/session`) with `nonce` = our attempt id,
  `successUrl`/`cancelUrl`/`errorUrl` = our return URL (`?result=`), `notifyUrl` = our webhook URL + `?t=<token>`,
  `expireDate` (wall clock, Africa/Addis_Ababa: OI-PAY-3), one item `{name, quantity 1, price (major units), description:
  order reference}`, `lang` EN. No `phone`/`email` (data minimisation; optional for the hosted page),
  `paymentMethods`/`beneficiaries` left to the merchant account (docs). Answer `{error, msg, data:{sessionId,
  paymentUrl, cancelUrl, totalAmount}}`; `totalAmount` must equal ours, `paymentUrl` must be https. Duplicate nonce →
  `session already exist`.
- **Verify:** `GET /api/sandbox/checkout/session/{sessionId}` (prod `/api/checkout/session/{id}`), merchant-key
  protected; returns `uuid`, `nonce`, `totalAmount`, `test` (sandbox flag), `transaction{transactionId,
  transactionStatus, paymentType, amount, fee}`. Mapping: SUCCESS (with a transaction id) → SUCCEEDED; PENDING without
  transaction id and payment type → NOT_STARTED (a fresh session reads PENDING); PENDING → PENDING; FAILED;
  CANCELLED/CANCELED; EXPIRED; anything else, UNAUTHORIZED/FORBIDDEN → UNKNOWN. `test` must match the environment, the
  paid `amount` must equal `totalAmount`. Unknown session → 404 `{error:true,"session not found…"}`; bad key → 404
  `"API Key Not found"` (MISCONFIGURED). **Doc mismatch:** the documented `GET /api/verify/transaction?uuid=` is 404 on
  the sandbox gateway (and the docs say it does not enforce the API key), so it is not used; `getSessionByNonce` is 404
  on the sandbox too — lost create answers are looked up by nonce in production only (OI-PAY-4).
- **Notifications:** ArifPay documents **no signature and no retries**. Payload (docs): `{uuid, sessionId, nonce,
  phone, paymentMethod, totalAmount, transactionStatus, transaction{transactionId, transactionStatus}}`. We authenticate
  with our own per-attempt token in the notify URL: `t = base64url(HMAC-SHA256(QAL_ARIFPAY_WEBHOOK_SECRET, "ARIFPAY|" +
  accountCode + "|" + nonce))`; missing/wrong → 401, stored INVALID. Even a valid one only triggers verification.
  Stored payload drops `phone`. (Confirm the query string survives, OI-PAY-2; if not, every notification is 401 and the
  reconciliation job still settles payments.)
- **Method discovery:** not offered by ArifPay's API (the session's `paymentMethodTypes` reflects the merchant account);
  the single hosted-checkout method is configured as data.

## Orders and attempts (requirement 4)
- `POST /billing/orders`: registered users only (guests 403 `GUEST_NOT_ALLOWED`); `Idempotency-Key` per user (same body
  → same order, other body → 409 `IDEMPOTENCY_KEY_REUSED`); `marketCountry` must equal the resolved market (403
  `BILLING_POLICY_DENIED`); policy must allow a gateway flow; price from `billing_prices` (ETB minor units) for product,
  period, market, channel, renewal model `MANUAL_RENEW`; snapshot list/discount/tax/total; at least one method eligible
  for the amount; `expires_at` = now + 30 min. Not in A1: promotions, upgrades (M4.4), credit packs, AUTO_RENEW.
- `POST /orders/{id}/attempts`: method from the routing layer; attempt committed INITIATED (nonce = attempt id) before
  the provider call, then REQUIRES_ACTION with the checkout URL; provider down → attempt UNKNOWN, 503
  `PROVIDER_UNAVAILABLE`; refusal → FAILED, 422. An open, unexpired checkout of the same method is returned instead of a
  second provider session (ArifPay: one session per order). `GET /orders/{id}` and `/attempts/{id}` are the polling
  endpoints (order shows `fulfilment.subscriptionId/receiptId` once paid). Cancel keeps open attempts open (a payment
  completed at the provider is still honoured).
- **Return URL (chosen):** `https://<api>/api/v1/billing/return/{attemptId}?result=success|cancel|error` — no bearer,
  never changes payment state, queues one verification (≤ 1 per 30 s per attempt), answers **303** to the
  allow-listed app URI `qalkeyboard://billing/return?orderId=…&attemptId=…&result=…` (`QAL_BILLING_APP_RETURN_URI`).
  The app's WebView intercepts either URL, closes the checkout and polls the order; a Custom Tab follows the 303 into
  the app. Chosen over a bare custom scheme because providers require http(s) URLs and the server sees the return.

## Webhooks, verification, entitlements (requirements 5, 6)
State machines (`PaymentStates`): attempts INITIATED → REQUIRES_ACTION/PENDING/UNKNOWN → SUCCEEDED | FAILED |
CANCELLED | EXPIRED; SUCCEEDED is terminal (an out-of-order FAILED never downgrades it); a closed attempt can still
become SUCCEEDED when the provider later verifies a capture (money taken is never ignored). Orders AWAITING_PAYMENT →
PAID | CANCELLED | EXPIRED | FAILED, closed → PAID likewise; a second verified capture on a PAID order is recorded and
flagged `refundRequired` (refund in A2), never granted twice.

`PaymentVerifier` is the only path to SUCCEEDED/PAID/terms: provider answer must match provider reference, nonce,
amount and currency (else UNKNOWN `VERIFICATION_MISMATCH`, nothing granted); then in one transaction: attempt,
append-only CAPTURE row (idempotent per attempt), order PAID, `Fulfilment` (new `user_subscriptions` row, source PAYMENT,
MANUAL_RENEW, 30/365 days; same plan already active → **stacked** from the current period end, event EXTENDED),
`subscription_payment_allocations`, `subscription_events`, `payment_receipts` (`ET-2026-000001` from
`billing_receipt_sequences`), outbox `billing.subscription.changed {userId, subscriptionId, orderId}`. The entitlements
module consumes it and invalidates its cache (it keeps reading the tables itself; billing never touches entitlement
tables). Webhook intake stores every event once on `(provider, external_event_id)` (sha256 of the body when the provider
has no id), forged ones under `invalid:<sha256>` so they can never occupy a real event's key; processing runs in the
outbox (retries when the attempt is not committed yet or the provider is down).

## Reconciliation hook (requirement 7)
db-scheduler task `billing-payment-reconciliation` (every 2 min): open attempts older than 10 min (`stuck-after`),
re-checked at most every 5 min, up to 48 h (`give-up-after`), go through the same verifier; a never-started checkout is
EXPIRED 10 min after its expiry; unpaid orders past expiry without open attempts become EXPIRED. The provider-vs-ledger
report is A2. Settings `QAL_BILLING_RECONCILIATION_*`.

## Security and privacy (requirement 8, N2, N7)
No secrets in the database (only `env:` reference names), logs or responses; adapters' `toString` redact keys. Logs
carry ids, codes, statuses and countries only — no IPs (beyond existing request logging), no payloads, no phone numbers,
no provider prose. Webhook bodies are stored redacted (no phone). Money is integer minor units. Webhooks and the return
URL are unauthenticated by design and can only trigger verification.

## Deviations needing an SD / plan amendment (owner decision 2026-11-20)
1. **SD §16.4** says "Chapa first, ArifPay second": ArifPay is now the Ethiopian default, Chapa the alternative.
2. **Plan M4.9** says "ArifPay adapter behind a feature flag, off by default": the adapter exists only when its key is set
   and every provider/account/policy switch starts off, but ArifPay is the default route.
3. **SD §16.7** integrates Google Play / App Store directly: the global option becomes RevenueCat (store-managed, still
   `store_purchases`, N6) — A2.
4. **SD §16.8, the `users.market_country_code` comment in V1 and the former contract text** say the market is decided
   from account/storefront/user selection, "not from IP address": the owner's design makes a **trusted edge IP country**
   primary, checked against network/SIM hints. `users.market_source` is not written by this step.

## Follow-ups
A2: Chapa adapter (+ method discovery), RevenueCat `StoreBillingProvider`, store notifications, refunds of duplicate
captures, provider-vs-ledger reconciliation. B: Android paywall (send `X-Qal-Network-Country`/`X-Qal-Sim-Country`,
`distributionChannel` from the flavour), WebView intercepting `/billing/return/` and `qalkeyboard://billing/return`.
Later: renewal reminders (M4.3; `/renew` and `renewable`), promotions/upgrades (M4.4), the billing data-export
participant (ADR 0022: `payment_attempts`/`payment_transactions` are now written), admin UI for policies.

## Step A2: Chapa adapter (2026-11-20)
Docs: https://developer.chapa.co/ (read 2026-11-20; no live key yet, built against WireMock stubs written from the docs,
`infra/compose/wiremock/mappings/chapa-*.json`). Package `billing.chapa`; the adapter exists only when `CHAPA_SECRET_KEY`
is set. One host for both modes (`https://api.chapa.co`); the key decides (a `CHASECK_TEST-` key must run with
`QAL_CHAPA_ENVIRONMENT=SANDBOX`, a live `CHASECK-` key with `PRODUCTION`, else the app refuses to start).

- **Initialize** `POST /v1/transaction/initialize`, `Authorization: Bearer <key>`: `amount` ("299.00"), `currency` ETB,
  `tx_ref` = attempt id (Chapa refuses a reused one: "Transaction reference has been used before"), `return_url` = our
  return URL, `callback_url` = the return URL without query (Chapa calls it with a GET `?trx_ref&ref_id&status`; it
  only queues a verification), `customization {title, description}` in plain characters. No e-mail, name or phone.
  Answer `{status:"success", data:{checkout_url}}`; no session id, so `tx_ref` is the provider reference.
- **Verify** `GET /v1/transaction/verify/{tx_ref}`: `data.status` success (with Chapa `reference`) / pending / failed /
  cancelled; `mode` must be `test` for SANDBOX and `live` for PRODUCTION; the currency must be the order's; the core
  compares the amount. 404 "Payment not paid yet" / "Transaction not found" = NOT_STARTED (expires after the attempt
  expiry). refunded / reversed read UNKNOWN with a code.
- **Webhooks** (dashboard URL `/api/v1/billing/webhooks/CHAPA/{chapa-et-sandbox|chapa-et-production}`, secret hash =
  `CHAPA_WEBHOOK_SECRET`): valid when `x-chapa-signature` = hex HMAC-SHA256(secret, payload) (raw body, or its compact
  re-serialisation because Chapa's sample hashes `JSON.stringify(req.body)`), or `chapa-signature` = the payload HMAC or
  hex HMAC-SHA256(secret, secret) as documented (switch the latter off with
  `QAL_CHAPA_ACCEPT_SECRET_HASH_SIGNATURE=false`). Dedupe key `chapa:<event>:<reference>:<status>` (Chapa sends no id).
  Payout events are dropped. As for ArifPay, a webhook only triggers verification.
- **Refunds** (`POST /v1/refund/{reference}`, `GET /v1/refund/{ref}/verify`) are implemented in the adapter
  (`supportsRefunds`) but **not used yet**: duplicate-capture refunds and the provider-vs-ledger report are deferred
  (OI-PAY-12). **Method discovery:** Chapa documents no pay-in method API (`/v1/banks` is for payouts).
- Doc ambiguities: OI-PAY-11.

**Chapa go-live** (after merchant onboarding; everything seeded off by V26):
```sql
UPDATE payment_providers SET provider_status = 'ACTIVE', is_active = TRUE WHERE provider_code = 'CHAPA';
UPDATE payment_provider_accounts SET account_status = 'ACTIVE', is_active = TRUE WHERE account_code = 'chapa-et-production';
UPDATE provider_payment_methods SET availability_status = 'AVAILABLE', is_enabled = TRUE, last_verified_at = NOW()
 WHERE method_code = 'CHAPA_CHECKOUT';
UPDATE payment_provider_account_methods SET availability_status = 'AVAILABLE', is_enabled = TRUE, last_verified_at = NOW()
 WHERE provider_account_id = (SELECT id FROM payment_provider_accounts WHERE account_code = 'chapa-et-production');
```
Runtime: `CHAPA_SECRET_KEY` (live), `QAL_CHAPA_ENVIRONMENT=PRODUCTION`, `CHAPA_WEBHOOK_SECRET`, `QAL_PUBLIC_BASE_URL`.
Chapa stays the ET alternative at priority 2; making it the default is the admin `PUT` in "Switching providers"
(proved end to end by `BillingChapaRevenueCatIT`: options and the checkout provider follow, no deploy).

## Open items
OI-PAY-1 … OI-PAY-14 in `docs/adr/0001-open-items.md`. RevenueCat: ADR 0046.
