# ADR 0012 — Destination profiles and Android app rules

**Status:** Accepted · **Date:** 2026-11-18 · **Plan step:** B3.4 (depends on B3.3, B1.5) · **Spec:** SD §10.3, §11.5;
contract `/me/destination-profiles`, `/me/app-destination-rules`; V1 `user_destination_profiles`,
`user_app_destination_rules`; ADR 0005, ADR 0007 D2/OI-P7, ADR 0010 (open question for B3.4)

## Context
Destination profiles are user-named contexts ("Mum - Amharic, honorific"). Their label is user-authored and must be
ciphertext. Android per-app rules map `HMAC(userKey, packageName)` to a profile or language; the server must never store
a package name. The contract fixes the hash format (64 lower-case hex) but defines no way for a client to obtain the key,
and ADR 0010 left open that a per-account key makes guest rules unusable on the target's devices.

## Decisions

### D1. Label
`label_ciphertext` only, via `UserDataEncryption`, bound to user, key version, table, column and row id (generated before
encrypting). Responses decrypt for the owner. No plaintext copy, no search index, no log of labels.

### D2. Rule key: per account, server-generated, stored encrypted
- The key is 256 random bits, one per account (not per device, not per install), so every device of the account computes
  the same hash and rules survive reinstall and a new phone.
- It is a **separate** key from the user data key and from the future dictionary HMAC key (ADR 0007 D2). Reasons: the
  data key rotates and the HMAC key must not (a rotation would change every hash); and a leaked hash key must not open
  data. It follows the same pattern as the OI-P7 key (separate, never rotated with the DEK, destroyed with the user) but
  is stored as ciphertext under the user's data key (`user_app_rule_keys`, row id = user id) rather than wrapped by KMS
  directly, so crypto-shred makes it unreadable with everything else. If B3.6 introduces a dedicated HMAC key store, this
  key can move there without changing the hashes. Caveat: a data-key rotation must re-encrypt this row (as for every
  ciphertext column; rotation re-encryption is not implemented yet, ADR 0005), never regenerate the key.
- It is created on the first key read (`GET /me/app-destination-rules/key`) or with the first non-empty rules document,
  whichever is first, so "has rules" implies "has a key".
- **Stated limit:** the server can decrypt the key, so an actor with database plus KMS access could test known package
  names against stored hashes. The guarantee is "no raw package name or reversible app list is stored or sent": a database
  dump, backup or log leak reveals nothing without the user's data key. A key the server cannot read would need a
  user-held secret, which phone/Google accounts do not have.
- **Delivery (ADR 0007 D24):** `GET /me/app-destination-rules/key` returns `{keyId, key}` (standard base64 of the 32
  bytes; `keyId` = first 8 bytes of SHA-256(key) in hex), bearer required (registered or guest), `Cache-Control:
  no-store`, never logged, **create on first read** and idempotent (same key every time, under the per-user advisory
  lock). Not rate limited. Clients must not invent a local key: it would be lost on reinstall and differ per device.

### D3. Guest merge (resolves the ADR 0010 open question)
- Both accounts have a key: the guest's rules cannot be re-hashed (the server never sees package names), so they are
  dropped; profiles still move. The target's devices re-create rules from their own on-device app list; the target's
  rules revision is unchanged (its document did not change).
- Only the guest has a key: it moves to the target (re-encrypted), so the moved rules keep matching; the target's rules
  revision is bumped.
- Neither has a key: rules (none can exist through the API) move as before.
- A sign-in that merely upgrades the guest in place (new phone/Google subject) keeps the same user id and key.

### D4. Validation and concurrency
Hash must match `^[0-9a-f]{64}$` (a package name never does). Rules use a `revision` document (V8
`user_app_rule_revisions`); profiles have no revision in the contract, so PUT is last-writer-wins. A per-user advisory lock
serialises profile create/delete and the rules PUT (cap, no rule for a vanishing profile). The cap of 100 profiles per
user is a server choice (neither contract nor SD defines one).

### D5. Schema gaps closed in V8
The contract carries `defaultStyle`, `glossaryId` and rule `enabled`, V1 had no columns: added (nullable/defaulted). The
contract `Tone` has no NATURAL while V1 defaults the column to it: an absent tone is stored NATURAL and omitted on read.
Glossary: only the caller's own glossaries are accepted (organisation glossaries are not referenced from profiles yet).

## Consequences / open
- Contract gaps closed in the D20 batch: key delivery; `DestinationProfile` is now a flat schema (the `allOf` made the
  validator reject correct responses; the test overlay is gone); 401 declared on the writes; `Tone` documents that
  `NATURAL` is deliberately omitted. Still open: no `isDefault` field; no limit stated for profile count; profile PUT has
  no 409.
- Merge drops guest rules when both accounts have keys: acceptable because rules are cheap to re-create and local recency
  stays on device (SD §10.3).
