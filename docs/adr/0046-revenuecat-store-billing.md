# ADR 0046 — RevenueCat as the global store-billing provider

**Status:** Accepted (step A2 of the payments work) · **Date:** 2026-11-20 · **Builds on:** ADR 0045 (routing, the
`StoreBillingProvider` hook, ZZ routes to REVENUECAT) · **Rules:** N6 (store purchases in `store_purchases`, never gateway
orders), N7 (never grant from an unverified payment), N2 (no payer data in logs) · **Spec:** SD §15, §16.7 (amended by
ADR 0045 deviation 3: RevenueCat instead of direct Play / App Store integration) · **Contract:** `verifyStorePurchase`,
`receiveStoreNotification`, `listSubscriptions`.

## Decision
Outside Ethiopia (routes `ZZ`) the app sells through Google Play (later the App Store) via the RevenueCat SDK. The
server never trusts the app or a webhook body: it reads the customer from the RevenueCat REST API and derives
`store_purchases` and STORE_MANAGED subscription terms from that answer. Docs read 2026-11-20:
https://www.revenuecat.com/docs/integrations/webhooks (+ event types, sample events, event flows),
https://www.revenuecat.com/docs/api-v1 (customers), https://www.revenuecat.com/docs/customers/identifying-customers.

## Identity and products
- **App User ID = our `users.id`** (= the access token `sub`; the server always reads `GET /v1/subscribers/{userId}` of the authenticated caller), the lower-case UUID (random v4: not guessable, no e-mail or phone, no `/`, under 100
  characters). The app configures the SDK with it after sign-in and never calls `logOut()` (no anonymous ids). Guests
  cannot buy. Event references that are not our user ids (`$RCAnonymousID:…`) are ignored.
- **Products → plans are data** (`external_product_mappings`, provider REVENUECAT, per environment; V26 seeds them
  inactive): Play subscriptions `qal_premium` and `qal_premium_plus`, base plans `monthly` and `annual`
  (`external_product_id` = subscription id, `external_offer_id` = base plan). RevenueCat product identifier =
  `subscriptionId:basePlanId` (e.g. `qal_premium:monthly`); suggested RevenueCat entitlements `premium` /
  `premium_plus` (informational; the plan comes from the product mapping). Each mapping points to an inactive
  STORE_MANAGED price row (amount unused: the store sets the price).

## Flow
1. App buys with the SDK, then `POST /api/v1/billing/store-purchases` `{"provider":"REVENUECAT","productId":"qal_premium:monthly"}`
   (Idempotency-Key). The server calls `GET https://api.revenuecat.com/v1/subscribers/{userId}`
   (`Authorization: Bearer REVENUECAT_SECRET_API_KEY`) and syncs every mapped subscription; 200 with the purchase and
   its subscription, 422 `STORE_PURCHASE_INVALID` when RevenueCat does not list that product for the caller (or it is of
   the other environment), 503 when RevenueCat is down. The A1 Play shape `{"provider":"GOOGLE_PLAY","productId":…,
   "purchaseToken":…,"packageName":…}` (what the Android step sends) is accepted unchanged and verified the same way
   through `qal.billing.store-verification-provider` (`QAL_BILLING_STORE_VERIFICATION_PROVIDER`, default REVENUECAT)
   while no direct Play adapter exists; the token is never trusted, stored or logged.
2. RevenueCat webhooks: `POST /api/v1/billing/store-notifications/REVENUECAT`. `Authorization` must equal
   `REVENUECAT_WEBHOOK_AUTH` (constant time); with `REVENUECAT_WEBHOOK_SIGNING_SECRET` set, `X-RevenueCat-Webhook-Signature`
   (`t=…,v1=hex HMAC-SHA256("<t>.<raw body>")`, ≤ 5 min) too. Stored once per event `id` in `payment_webhook_events`
   (subscriber attributes dropped), answered exactly 200, processed through the outbox: each referenced user of ours
   (`app_user_id`, `original_app_user_id`, `aliases`, `transferred_to/from`) is re-synced from the REST API. Event order
   and duplicates therefore do not matter; RevenueCat unreachable = outbox retry. The redacted event is appended to
   `store_purchase_events` of the purchase it names.
3. Sync (`StorePurchases`, provider-agnostic): one `store_purchases` row per user and store product; status from the
   v1 subscription: refunded → REFUNDED; period running → ACTIVE (also when cancelled, until the end); billing grace →
   IN_GRACE_PERIOD; `auto_resume_date` → PAUSED; still renewing and < 6 h past the end → ACTIVE (Play renewal gap,
   `QAL_BILLING_STORE_RENEWAL_PENDING`); billing issue < 60 days → ON_HOLD; else EXPIRED; a purchase RevenueCat no
   longer lists for the user (TRANSFER) → REVOKED. The `user_subscriptions` row (source STORE, STORE_MANAGED,
   `auto_renew_enabled` from the store) maps ACTIVE→ACTIVE, IN_GRACE_PERIOD→GRACE_PERIOD, ON_HOLD→PAST_DUE,
   PAUSED→PAUSED, REFUNDED/REVOKED→REVOKED, EXPIRED→EXPIRED; every change writes `subscription_events` and the outbox
   `billing.subscription.changed` (entitlements cache dropped). Only ACTIVE / GRACE_PERIOD grant; the entitlement never
   depends on the user's current country. `/me/subscriptions` shows `manageUrl` (store management URL).
4. Environment: `QAL_REVENUECAT_ENVIRONMENT` (SANDBOX default) decides which purchases grant; `is_sandbox` purchases
   never grant on a PRODUCTION server and vice versa (license testers on production need a decision, OI-PAY-13).

REST API v1 is used because the webhook docs recommend `GET /subscribers` after every event; v2
(`/v2/projects/{id}/customers/{id}/subscriptions`, separate keys, no create-on-read) is a later option.

## Config / env
`REVENUECAT_SECRET_API_KEY` (adapter on when set), `REVENUECAT_WEBHOOK_AUTH` (≥ 32 chars under prod-like profiles),
`REVENUECAT_WEBHOOK_SIGNING_SECRET` (optional), `QAL_REVENUECAT_ENVIRONMENT`, `QAL_REVENUECAT_BASE_URL`,
`QAL_REVENUECAT_CONNECT_TIMEOUT` / `_READ_TIMEOUT`, `QAL_BILLING_STORE_RENEWAL_PENDING`,
`QAL_BILLING_STORE_VERIFICATION_PROVIDER`.

## Go-live checklist
1. Publisher merchant profile in a merchant-supported country (OI-PAY-7).
2. Play Console: subscriptions and base plans above; RevenueCat project, Play app, products, entitlements, offering;
   Play real-time developer notifications to RevenueCat.
3. RevenueCat webhook to `https://<api>/api/v1/billing/store-notifications/REVENUECAT` with a ≥ 32-character
   Authorization value (optionally a signing secret); send a TEST event (stored and ignored).
4. Env vars above, `QAL_REVENUECAT_ENVIRONMENT=PRODUCTION`.
5. Data:
   ```sql
   UPDATE payment_providers SET provider_status = 'ACTIVE', is_active = TRUE WHERE provider_code = 'REVENUECAT';
   UPDATE external_product_mappings SET is_active = TRUE
    WHERE environment = 'PRODUCTION' AND provider_id = (SELECT id FROM payment_providers WHERE provider_code = 'REVENUECAT');
   UPDATE billing_market_channel_policies
      SET approval_status = 'APPROVED', reviewed_at = NOW(), reviewed_by_user_id = '<reviewer users.id>',
          purchase_ui_allowed = TRUE
    WHERE country_code = 'ZZ' AND client_platform = 'ANDROID' AND distribution_channel = 'GOOGLE_PLAY';
   ```
   (ZZ/DIRECT_DOWNLOAD stays unapproved: a sideloaded APK has no Play Billing; OI-PAY-13.)

## Not in this step
Direct Play / App Store verification (those shapes go through RevenueCat), non-subscription store
products, a live RevenueCat test (no keys yet, OI-PAY-14), refunds and the provider-vs-ledger report (OI-PAY-12).
Open items: OI-PAY-7, OI-PAY-13, OI-PAY-14.

## Amendment (2026-11-25, T8.10 store-billed credit packs)
Owner rule: all billing outside Ethiopia uses Google Play billing through RevenueCat. Non-subscription store products are
now in scope for the prepaid AI credit packs (ADR 0048 §4): Play **consumables** `credits_100` / `credits_500` /
`credits_1500` (V32 mappings, inactive), read from `subscriber.non_subscriptions`, recorded as `store_purchases` rows of
`purchase_type = CONSUMABLE` keyed by the store transaction id, each granting exactly one `ai_credit_lots` row
(`store_purchase_id`; a PURCHASE lot is backed by an order xor a store purchase). The same verify-then-grant rule applies
(client report and `NON_RENEWING_PURCHASE` webhook both re-read the customer); a `CANCELLATION` confirmed by the REST
API reverses the unused credits. Store providers are never offered to a `DIRECT_DOWNLOAD` build. Details and the go-live
steps: backend README "Billing: store-billed credit packs outside Ethiopia".
