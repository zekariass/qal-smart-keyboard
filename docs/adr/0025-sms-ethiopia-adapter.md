# ADR 0025 — SMS Ethiopia adapter

**Status:** Accepted · **Date:** 2026-11-18 · **Plan step:** B2.1 follow-up (OI-11) · **Decision:** ADR 0007 D48 ·
**Spec:** `docs/vendors/sms-ethiopia-api.md` (vendor docs summary), ADR 0006 (phone OTP), SD §8.2, §18 (logging hygiene).

## Context
D48 picks SMS Ethiopia for sign-in codes. Its API only accepts Ethio Telecom mobiles (`2519` + 8 digits). The auth/OTP
layer refuses other numbers before any SMS is sent (D48); the adapter is the last line and must not leak the number, the
code or the API key, must fail closed at startup, and must tell operators apart "the user typed a bad number" from "we
are out of credit".

## Decision
- **Selection:** `qal.notifications.sms.provider=sms-ethiopia` (env `QAL_SMS_PROVIDER`). Allowed under
  `prod`/`production`/`staging`. Settings `qal.notifications.sms.sms-ethiopia.*`: `api-key` (env `QAL_SMS_ETHIOPIA_API_KEY`,
  **required**, startup fails if blank; redacted in every `toString`), `base-url` (env `QAL_SMS_ETHIOPIA_BASE_URL`, default
  `https://smsethiopia.com/`, must be `https` under production-like profiles), `connect-timeout` 2 s, `read-timeout` 5 s.
- **Wire:** v2 only, `POST /api/v2/sms/send`, header `KEY` (no `Authorization`), body `{"msisdn","text"}` with the `+`
  stripped. RestClient on a JDK HttpClient, HTTP/1.1, redirects never followed. Only `+2519\d{8}` is sent; anything else
  fails locally with `INVALID_NUMBER` and no call.
- **Typed failures:** `SmsProvider.send` now throws `SmsDeliveryException(reason, retryAfter)` (no cause, no provider
  text). `SmsOtpSender` still wraps it in `OtpDeliveryException`; `OtpService` behaviour is unchanged.

| Vendor response | Reason | Alert | Meter `code` |
|---|---|---|---|
| 200 `status=ACCEPTED` | sent (message id logged) | | `none` |
| 200 unreadable / other status | `INVALID_RESPONSE` | yes | `malformed_body`, `not_accepted` |
| 400 `Error Code :: 10000` | `INVALID_NUMBER` | no | `10000` |
| 400 10001 (empty message) | `INTEGRATION_ERROR` | yes | `10001` |
| 400 10002 (campaign inactive), 10006 (sender ID not approved) | `PROVIDER_MISCONFIGURED` | yes | `10002`, `10006` |
| 400 10005 (insufficient balance) | `INSUFFICIENT_BALANCE` (ERROR log `INSUFFICIENT SMS BALANCE`) | yes | `10005` |
| 400 10007 (test campaign, not whitelisted) | `RECIPIENT_NOT_WHITELISTED` | no | `10007` |
| 400 other code / field-keyed validation body | `INTEGRATION_ERROR` | yes | `unknown`, `validation` |
| 401, 403 | `AUTHENTICATION_FAILED` | yes | `http_401`, `http_403` |
| 429 | `RATE_LIMITED` (+ `Retry-After`) | no | `http_429`; `local_backoff` while blocked |
| 3xx, 404, other 4xx | `INTEGRATION_ERROR` | yes | `http_3xx`, `http_404`, `http_4xx` |
| 5xx, timeout, I/O | `UNAVAILABLE` | no | `http_5xx`, `timeout`, `io_error` |
| local refusal | `INVALID_NUMBER` / `INTEGRATION_ERROR` (blank text) | | `local_reject` |

- **Retries:** none, as before (`SmsOtpSender` and `OtpService` do not retry; the user asks for a new code after the
  cooldown). A numeric `Retry-After` on 429 is honoured by refusing sends locally until it elapses, capped at 5 minutes
  (the vendor limit is per account, so hammering it helps nobody).
- **Logs and metrics:** the 400 body is read (max 16 KiB) only to extract the code with `Error Code\s*::\s*(\d+)`; the
  `error_message` text is never logged (it can echo the number). Logs carry reason, vendor code, HTTP status, segment count
  and the vendor message id (ULID, not personal data). New meter
  `qal.notifications.sms.provider.responses{provider,outcome,code,alert}`, bounded tags; the existing
  `qal.notifications.sms.sent{provider,outcome}` is unchanged. Alert on `alert="true"`, page on `outcome="insufficient_balance"`.
- **Text:** the English OTP text (~80 GSM-7 characters) stays one segment (160 GSM-7, 70 UCS-2/Amharic). A localised
  Amharic template must stay within 70 characters. A multi-segment send logs a WARN.
- **Local dev:** the compose WireMock now stubs `/api/v2/sms/send` (`sms-ethiopia-*.json`, magic numbers for 10005, 10007,
  429, 500; 401 without `KEY`; 10000 for a malformed msisdn). The placeholder `/sms/v1/messages` stubs are gone.

## Consequences
- Production needs a paid campaign with an Ethio Telecom-approved sender ID and an API key from that campaign in
  `QAL_SMS_ETHIOPIA_API_KEY`. The free Default campaign only reaches dashboard-whitelisted numbers (10007 otherwise).
- Keys expire and campaigns run out of credit: both surface as alertable outcomes, not as user errors.
- Delivery reports (`GET /api/v2/sms/{id}`, webhook add-on) are not used; 200 means accepted and queued only.
- ADR 0006 still mentions the `/sms/v1/messages` placeholder stub; this ADR supersedes that sentence.

## Open points (vendor spec)
- The docs were extracted from a client-rendered page; verify with the vendor before go-live.
- 429 headers documented are `X-RateLimit-*`; `Retry-After` is not documented (honoured only if present).
- 403 is not documented (treated like 401). Error codes 10003/10004 are not documented (→ `INTEGRATION_ERROR`, `unknown`).
- The free test campaign appends a footer, so test SMS are longer than production ones.
