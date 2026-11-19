# ADR 0033 — E-mail and password sign-in

**Status:** Accepted · **Date:** 2026-11-19 · **Plan step:** owner request (sign-in beyond phone) ·
**Related:** ADR 0006 (phone OTP, sessions), ADR 0008/0010 (guests, linking), ADR 0009 (Google: no linking by e-mail),
ADR 0015 (deletion), ADR 0020 (retention), ADR 0022 (export), ADR 0007 D28 (dev capture endpoints).

## Context
Sign-in worked only with an Ethiopian phone number (Google is built but not configured). The owner asked for e-mail sign-in
too and, for quick tests, for e-mail sign-up **without** verification, with a switch to require verification later. No
e-mail delivery vendor exists yet. The contract already declared `/auth/email/register`, `/auth/email/login`,
`/auth/password/forgot` and `/auth/password/reset`; V1 has `users.email`/`email_normalized`/`email_verified_at`,
`user_password_credentials` (hash, `failed_attempts`, `locked_until`) and the `EMAIL` identity provider.

## Decision

### Switches (`qal.identity.email.*`, `qal.notifications.email.*`)
| Key | Base default | `dev` profile | |
|---|---|---|---|
| `qal.identity.email.enabled` (`QAL_EMAIL_ENABLED`) | `true` | `true` | off: every e-mail endpoint answers 503 `EMAIL_NOT_CONFIGURED` |
| `qal.identity.email.verification-required` (`QAL_EMAIL_VERIFICATION_REQUIRED`) | `true` | `false` | see below |
| `qal.notifications.email.provider` (`QAL_EMAIL_PROVIDER`) | `none` | `capture` | `log`/`capture` refused under prod/production/staging |
| `qal.identity.email.code-ttl` / `resend-cooldown` / `max-attempts` / `max-sends` / `challenge-max-age` | 15m / 60s / 5 / 5 / 24h | | |
| `qal.identity.email.lockout.max-failures` / `duration` | 10 / 15m | | |
| `qal.identity.email.argon2.*` | m=19456 KiB, t=2, p=1, max-concurrent 2×CPUs, max-wait 5s | | |
| `qal.identity.email.limits.*` | see README | | |

**Startup:** enabled + verification required + provider `none` **fails** (nobody could finish a sign-up). Verification off
under a production-like profile is **allowed** (the owner asked for the switch) but logs a loud WARN. Provider `none` logs a
WARN: password reset, code resend and (if it were on) verification answer 503 `EMAIL_NOT_CONFIGURED`. So production today
runs with `QAL_EMAIL_VERIFICATION_REQUIRED=false` (unverified sign-up, no reset) or `QAL_EMAIL_ENABLED=false`.

### Flows
- **Register** `{email, password, displayName?, ageConfirmed: true, device?}`.
  - Verification **off**: 201 `AuthSession`; the account is `REGISTERED`/`ACTIVE`, `email_verified_at` NULL
    (`user.emailVerified` false). A live account with the address: 409 `EMAIL_ALREADY_REGISTERED`.
  - Verification **on**: 202 `EmailVerificationChallenge {challengeId, expiresAt, resendAvailableAt, codeLength}`.
    **No account is created**: an `email_challenges` row (`REGISTRATION`) holds the Argon2id hash of the chosen password
    and the address + display name sealed with AES-256-GCM. `POST /auth/email/verify {challengeId, code}` creates the
    verified account and signs in (200, `isNewUser` true). We chose "no account until verified" over "pending account
    with restricted tokens": nothing to restrict, no half-accounts, and an unverified sign-up never claims the address.
  - A guest bearer is linked as for phone/Google (ADR 0010): upgraded in place on creation (verification off: at
    register; on: at verify), merged into an existing account otherwise.
- **Verify** binds the code to the **challenge id**, not just the address. If an attacker starts a sign-up for the
  victim's address with their own password, the victim's mailbox receives that code too; typed into the victim's own app
  it does not match the victim's challenge, so the victim can never complete the attacker's sign-up by mistake.
  Wrong codes: 401 `OTP_INVALID` (+`attemptsRemaining`), 5 wrong → 429 `OTP_TOO_MANY_ATTEMPTS`; expired → 401
  `OTP_EXPIRED`. **Resend** `{challengeId}` replaces the code in place (old code dies, attempts reset), after the 60 s
  cooldown, at most 5 sends per challenge, within 24 h.
- **Login** `{email, password, device?}`: the account is read without a lock, then **one Argon2id verification always
  runs** (against a dummy hash when the address is unknown or has no password), then the transaction (per-address
  advisory lock, then guest row, then account row, the lock order of every sign-in) counts a wrong password. Unknown
  address and wrong password are the same 401 `INVALID_CREDENTIALS`. 10 wrong in a row lock the credential for 15 min:
  429 `ACCOUNT_LOCKED` with `Retry-After` (also for the right password while locked). A reset clears the lock. Suspended:
  403 `ACCOUNT_SUSPENDED`. With verification required and an unverified address: 403 `EMAIL_NOT_VERIFIED` with the
  extension members `challengeId`, `expiresAt`, `resendAvailableAt`, `codeLength`; a code is e-mailed (the open challenge
  is reused inside the cooldown, so repeated logins send nothing new). A stored hash with weaker parameters is re-hashed
  after a successful login.
- **Forgot** `{email}`: always 202 (unless the per-IP or per-address limit, or 503 without a provider - all independent of
  whether an account exists). A code is generated only for a live account with a password; it is e-mailed on a virtual
  thread so the response time does not depend on the account either. A new code supersedes earlier ones.
- **Reset** `{email, token, newPassword}` (`token` is the 6-digit code; `email` was added to the contract's
  `PasswordResetRequest`, which had no client or implementation): every failure of the code is the same 400
  `RESET_CODE_INVALID` (no `attemptsRemaining`, no "expired", so it cannot be used to learn that an account exists).
  A weak new password is 400 `VALIDATION_FAILED` before the code is touched. Success: new hash, lock cleared,
  `email_verified_at` set (the code proved the mailbox, also with verification off), all open e-mail challenges of the
  account consumed, **every session family revoked** (`PASSWORD_CHANGED`), 204. Access tokens already issued live out
  their 10-15 minutes, as for logout.

### Squatting (unverified accounts)
With verification off, anyone can create an account with someone else's address. When verification is turned on later:
- A **verified** account blocks registration (409). An **unverified** one does **not**: registration starts a normal
  `REGISTRATION` challenge (nothing changes yet). Only when the code proves control of the mailbox is the unverified
  account **taken over**: its password is replaced with the one chosen in this sign-up, every session of it is revoked,
  all its open challenges are consumed, the address becomes verified, and the mailbox owner is signed in to it
  (`isNewUser` false). Suspended or deletion-pending unverified accounts are not taken over (409 / 403).
- The squatter cannot lock out the owner: the owner can always register again (or reset the password) and prove the
  mailbox. The squatter cannot take anything over without the code.
- Accepted residue: data the squatter put into the account stays with it (the mailbox owner sees it); turning
  verification on does not revoke existing sessions of unverified accounts (they end on next sign-in or at refresh expiry).
- With verification off there is no mailbox proof, so a squatter wins the address until the owner resets the password
  (needs an e-mail provider). This is the accepted cost of the quick-test mode, hence the production WARN.

### Address and password rules
- Address: trimmed, one `@`, ASCII dot-atom local part (no quoted strings; non-ASCII local parts refused because
  PostgreSQL `LOWER` in `ck_users_email_normalized` and Java could disagree), IDN domains converted to punycode and
  lower-cased. `users.email` keeps the local part as typed; `email_normalized` and the `EMAIL` identity subject are
  fully lower-case (one account per address regardless of case). No Gmail dot/plus folding.
- Password (NIST SP 800-63B style): 10-128 Unicode code points after NFKC (NFKC also before hashing), no composition
  rules; refused: a small bundled list (`identity/common-passwords.txt`), a common base word with only digits/symbols
  around it, one or two repeated characters, a keyboard/alphabet/digit run, the address or its local part. Field codes:
  `PASSWORD_TOO_SHORT`, `PASSWORD_TOO_LONG`, `PASSWORD_TOO_COMMON`, `PASSWORD_MATCHES_EMAIL`; `INVALID_EMAIL` on `email`.

### Hashing
Argon2id through Spring Security's `Argon2PasswordEncoder` (Bouncy Castle `bcprov-jdk18on`, +7 MB; chosen over
Password4j, 90 KB but a single maintainer and its last release in mid-2025, because Bouncy Castle is maintained and
audited and Spring wraps it). Parameters: the OWASP Password Storage Cheat Sheet baseline m = 19 MiB, t = 2, p = 1,
16-byte salt, 32-byte hash, stored as the PHC string. At most `max-concurrent` hashes run at once (memory bound); a
request that waits longer than `max-wait` gets 429 with `Retry-After: 1`.

### Codes and storage
6 digits, uniform. V22 `email_challenges` (`REGISTRATION` / `EMAIL_VERIFICATION` / `PASSWORD_RESET`): the address only as
HMAC (`email_hash`), the code only as `v1:` HMAC over (challenge id, purpose, code), compared in constant time; keys
derived from `qal.identity.otp.secret-key` with labels distinct from phone OTP (no new secret to manage). V1
`password_reset_tokens` stays unused (no attempt counter, which a 6-digit code needs).

### Rate limits (fixed windows, per instance, PROVISIONAL)
register 10/h per IP (also curbs probing addresses through 409), login 60/h per IP and 30/h per device, verify 60/h per
IP, resend and forgot 20/h per IP, reset 30/h per IP; e-mails per address 5/h and 10/day across every flow.
409 on register tells that an address has an account: accepted for an app (common UX), limited per IP; login and forgot
do not tell.

### E-mail port
`notifications.EmailCodeSender` (identity's port: `available()`, `send(EmailCodeMessage)`) renders short plain-text
English messages without links and calls the provider SPI `notifications.email.EmailSender`. Providers: `none`,
`log` (masked address; body only with `qal.notifications.email.log.include-message=true`, dev only), `capture` with
`GET /dev/email/last?to=...` (dev only, loopback only, `qal.notifications.email.capture.endpoint-enabled`, same guards
as `/dev/sms/last`).

### Deletion, export, retention
Deletion (ADR 0015) deletes the `EMAIL` identity, the password credential and the account's `email_challenges`
(identity participant); the anonymise step nulls the address. `ux_users_email_normalized` ignores closed rows, and a
sign-up frees the `EMAIL` identity row of an account that is closed but not yet purged, so the address is reusable at
once. A pending sign-up (no account) is not personal data of an account; it expires in minutes and retention deletes it.
Export (ADR 0022): `account` already carries `email` and `email_verified_at`, `authIdentities` the `EMAIL` identity;
hashes and challenges are listed as excluded. Retention (ADR 0020): new rule `email-challenges`, 24 h after expiry.

## Open
- **E-mail vendor** (not decided): Amazon SES, Postmark, Resend, Brevo, or another. Criteria: deliverability to
  Ethiopian and Gmail inboxes, DKIM/SPF/DMARC on our domain, an EU/US data-processing agreement, price at low volume,
  bounce/complaint webhooks. The adapter is one `EmailSender` implementation selected in `EmailConfiguration`; then set
  `QAL_EMAIL_PROVIDER` and `QAL_EMAIL_VERIFICATION_REQUIRED=true`.
- Localised e-mail templates (am/ti/om), with the SMS templates.
- Distributed rate limits (as for OTP) before more than one instance.
- Optional: a "change password" endpoint for signed-in users, and adding e-mail to an existing phone/Google account
  (needs a linking design; ADR 0009's no-linking-by-e-mail rule stays).
