# ADR 0047 — Android payments UI: paywall, hosted checkout, store billing

**Status:** Accepted (step B of the payments work) · **Date:** 2026-11-20 · **Builds on:** ADR 0045 (payments core),
0013 (entitlement token), 0016 (transport security), 0032 (strings), 0036 (settings design) · **Plan:** M4.8, M4.10.

## Decision
- **Hints and channel.** `:api` `CountryHintsInterceptor` adds `X-Qal-Network-Country` / `X-Qal-Sim-Country`
  (`TelephonyManager.networkCountryIso` / `simCountryIso`, no permission) to `/api/v1/billing/…` calls only, and only
  valid ISO alpha-2 values (CDMA network country, a SIM not ready, Wi-Fi-only devices: no hint). The flavour's channel
  (`play` → `GOOGLE_PLAY`/`ANDROID_GOOGLE_PLAY`, `direct` → `DIRECT_DOWNLOAD`/`ANDROID_DIRECT`) is in `ClientInfo` and
  goes where A1 reads it: `distributionChannel` query (options) and `salesChannel` body (orders). There is no header.
- **Paywall** (`app/billing/PlansScreen`, replaces the placeholder): current plan (`/me/entitlements` + `/me/subscriptions`),
  server products/prices (minor units formatted, never computed), payment options in server order (default first),
  manual-renew terms; loading, signed out, offline, error, not available (market policy, or store flow on a build
  without the store SDK). Guests see prices and must sign in to buy. Hand-written JSON (`BillingClient`) so unknown enum
  values from a newer server never break the screen.
- **Hosted checkout** (`PlansModel`, `CheckoutScreen`): order (Idempotency-Key, reused on retry) → attempt → `checkoutUrl`
  in a locked-down WebView (JS + DOM storage only; no file/content access, no JS interface, no geolocation/camera,
  no new windows, mixed content never, Safe Browsing on, cookies/storage cleared on close). `CheckoutUrlPolicy`:
  `…/api/v1/billing/return/{uuid}` (any host) and `qalkeyboard://billing/return` close the WebView; https loads; app
  links go out through a sanitised `BROWSABLE` intent (no component, selector, flags, own package); everything else is
  blocked. The WebView never loads the return URL, so the app calls `GET /billing/return/{attemptId}` itself on its own
  API host (no bearer) to queue the server's verification, then polls `GET /billing/orders/{id}` (~3 min backoff, plus
  the attempt for an early FAILED). Only `PAID` counts: then `EntitlementManager.planChanged()` refetches the token past
  the client throttles so the keyboard unlocks at once, and a receipt is shown. No answer → "still confirming" with
  refresh / reopen; the pending order is kept in `noBackupFilesDir` and polled again after process death.
- **Store billing** (`play` only): RevenueCat 11.4.2 in `:billing-play` behind the app's `StoreBilling` port
  (`direct` has `Unavailable`). Public key from `qal.revenuecatAndroidKey` (empty = hidden; `sk_`/`atk_` fails the
  build). App user id = backend user id (`sub`) — **to be confirmed by A2**. Shown only when options say
  `STORE_BILLING` with a `REVENUECAT`/`APP_STORE` method. Purchases go to `POST /billing/store-purchases`; the plan comes
  from the server (also after a RevenueCat webhook). Logs at ERROR, no device-identifier collection; `AD_ID` removed.
- **Return:** `AiLink.UPGRADE` (keyboard lock badges, "See plans") opens the plans screen; leaving it returns to the
  typing app; in-app openers (themes, privacy, account row) return to their screen.

## Consequences / open
The `play` APK gains `com.android.vending.BILLING` (Play Billing Library, needed for any store purchase). No intent
filter for `qalkeyboard://` in the original design; M4.11 adds `BillingReturnActivity` (see the amendment). Device test needed
against the ArifPay sandbox via the dev backend.

## Amendment (M4.11): Custom Tab

**Why.** ArifPay's hosted checkout page asks for the device's geolocation and renders nothing when the in-app WebView
declines. The app has no location permission and must not request one, so the WebView is replaced by a Chrome Custom
Tab, where Chrome handles the page's location prompt itself. The WebView code, its settings and the URL-interception
helpers (`CheckoutUrlPolicy.decide`, `ExternalLinks`) are removed; no permission is added.

**Flow.** `CheckoutScreen` keeps its signature. Once per checkout URL (kept across rotation) it checks
`CheckoutUrlPolicy.isAllowedStart` (https with a host) and opens the page with `CustomTabsIntent` (title shown, toolbar
in the app theme colour); with no Custom Tabs provider it falls back to `ACTION_VIEW`; if nothing can open it the
existing error state shows. While the tab is open the screen shows "Finish paying on the ArifPay page" with "I've paid",
"Open payment page again" and "Cancel payment" (system Back = cancel).

**Return.** The server's `GET /billing/return/{attemptId}` still queues the verification and 303-redirects to
`qalkeyboard://billing/return?…`. A small exported `BillingReturnActivity` (translucent, `noHistory`, out of recents)
owns that intent filter, checks scheme, host and path only (the query is ignored) and starts `MainActivity` with
`CLEAR_TOP | SINGLE_TOP`, so the existing task and plans state come forward and no second `MainActivity` is created.
When the screen resumes after the tab was left (lifecycle `ON_PAUSE` then `ON_RESUME`), or "I've paid" is tapped, it
calls `onReturn(null)` once: the model signals the server and polls as before. This covers both ArifPay's done button
(deep link) and the user simply closing the tab. Nothing in the URI marks anything paid; only the server's `PAID` counts.
