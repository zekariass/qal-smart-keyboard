# SMS Ethiopia API — summary of the vendor's docs

Source: https://smsethiopia.com/#/landing/docs (client-rendered; text extracted from the site's JS bundle on 2026-11-18). Verify against the live docs or the vendor's support before go-live.

## Base and auth
- Base URL: `https://smsethiopia.com/` (always HTTPS; plain HTTP is redirected and clients drop the POST body). Do NOT use `api.smsethiopia.com`.
- Auth: header `KEY: <api key>` on every request (alias header `API_KEY` also accepted). NOT `Authorization: Bearer`.
- An API key belongs to exactly one **campaign**; the campaign's sender ID and prepaid SMS balance are used. Keys have an expiry date and can be deactivated in the console.

## Send (v1, live)  `POST /api/sms/send`
Body (JSON): `{"msisdn": "251911234567", "text": "Your OTP is 1234"}`
- `msisdn` required: string, **must start with `2519` and be exactly 12 digits** (no `+`). Only Ethio Telecom mobile numbers (9-prefix). Safaricom Ethiopia (`2517…`) is not accepted.
- `text` required. 160 GSM chars per SMS (153 per segment when split); Amharic/Unicode 70 chars (67 per segment). Each segment is deducted from the package.
- `messageType` optional (no values documented).
- No `sender_id` field: the sender comes from the campaign.
- Success 200: `{"sent": true, "id": 0, "description": "..."}` (`id` reserved, always 0 in v1). One example also shows `{"status": "success", "message": "Accepted successfully"}`; parse defensively.

## Send (v2, live)  `POST /api/v2/sms/send`  — recommended
Same headers and body as v1. Success 200:
```json
{ "id": "01JZX5M8Q3T5V0X8YW9RCB2K7D", "segments": 1, "status": "ACCEPTED" }
```
`id` is a string ULID. Status lookup `GET /api/v2/sms/{id}` (same KEY header) returns `{"id","msisdn","segments","status","createdAt"}`; statuses ACCEPTED → SENT → DELIVERED | UNDELIVERED. Foreign ids return 404.
Webhook add-on (optional, paid, enabled by support): POST to campaign URL on final state, payload `{"id","msisdn","status","timestamp"}`; 3 attempts (1 s, 5 s backoff), 5 s timeout. No signature documented.

Sending is asynchronous: 200 means accepted and queued.

## Errors
- Business errors: **HTTP 400** with body `{"error_message": "<text> Error Code :: <code>"}`, e.g. `"Campaign SenderID not approved yet, please contact with system Administrator. Error Code :: 10006"`.
- Missing/empty field: HTTP 400 keyed by field: `{"msisdn": "msisdn is mandatory", "text": "text is mandatory"}`.
- Codes (same for v1 and v2):
  - 10000 Invalid MSISDN (must start 2519, 12 digits)
  - 10001 Empty message
  - 10002 Campaign not activated (deleted campaigns are deactivated)
  - 10005 Insufficient SMS balance for the message's segments
  - 10006 Sender ID not approved (retry later, don't resubmit immediately)
  - 10007 Recipient not whitelisted (free test campaign only delivers to numbers verified in the dashboard)
- 401: key missing, invalid or deactivated. 404: unknown path / foreign v2 id. 429: rate limit (per account, all keys share; headers `X-RateLimit-Remaining`, `X-RateLimit-Limit`; back off). 500: server error.

## Account facts relevant to us
- New accounts get a free **Default** test campaign: 100 SMS, shared sender `8726`, delivers only to whitelisted (dashboard-verified) numbers, adds a test footer.
- Production: buy a paid campaign (prepaid package in ETB from a deposit balance), register a custom sender ID (approved with Ethio Telecom, typically 1–2 business days; PENDING sends fail with 10006), create an API key in that campaign.
- Delivery reports per message and webhook are paid add-ons.
