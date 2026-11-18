# ADR 0018 — Personal dictionary sync

**Status:** Accepted · **Date:** 2026-11-18 · **Plan step:** B3.6 (depends on B3.3, B1.5) · **Spec:** SD §7 user dictionary,
§10 sync, §19; contract `GET/POST /me/dictionary/changes`; V1 `user_dictionary_entries`; ADR 0005, ADR 0007 D2/OI-P7,
ADR 0010, ADR 0012, ADR 0015; A1.8/D18 (local dictionary, NFC)

## Context
The personal dictionary lives on the device. With the opt-in `DICTIONARY_SYNC` consent it syncs between the account's
devices. Words are user content: ciphertext at rest, uniqueness through a keyed HMAC (D17), tombstones for deletes.

## Decisions

### D1. The dictionary HMAC key
Per user, 256 random bits (`SecureRandom`), created on first push (or first guest merge into the account) and never
regenerated. Stored only as `UserDataEncryption` ciphertext (context: table `user_dictionary_sync_state`, column
`key_ciphertext`, row id = user id) so crypto-shred makes it unreadable. It is **not derived from the data key** and is
separate from it: a data-key rotation never changes a hash (the same pattern as the app-rule key, ADR 0012). The server can
unseal it (it computes the HMACs); the stored `word_hmac` values alone cannot be used to guess words without it.

`word_hmac = lower-case hex of HMAC-SHA256(key, "qal.dictionary.word.v1" || 0x00 || languageCode || 0x00 || NFC(word))`,
text as UTF-8. The tag separates domains, the language is part of the input (`ti` and `am` hash differently even for the
same word), case is not folded. `languageCode` is `supported_languages.language_code`.

### D2. Words
NFC-normalised on the server (clients should send NFC, D18). A word is 1-64 code points with no whitespace, control or
unassigned characters; otherwise that change is rejected with `INVALID_WORD` (the rest of the batch is applied). The
ciphertext is bound to the row id (generated before encrypting).

### D3. Sync protocol (the contract's token protocol)
- Every change that alters a row takes the next per-user `sync_version` from `user_dictionary_sync_state.sync_counter`
  (a counter, not `MAX(...)`, so versions stay monotonic if tombstones are purged one day). The state row is locked
  `FOR UPDATE` for the whole push (and guest merge), serialising writers per user; readers need no lock because versions
  commit in order.
- Pull: `since` is an opaque token (`v1.` + base64url of the last version seen; omit = full sync), results oldest first,
  `limit` 1-100 (default 20), `hasMore`. A deleted word is a `DELETE` change that still carries the word (so a client can
  find it). `clientChangeId` on download is the entry id. An unknown token is 400.
- Push: up to 500 changes applied in order. The returned `syncToken` is the high-water mark after the batch; a client
  that did not pull up to the version before its push keeps its own pull cursor.
- **Conflict rule: last writer wins per word in server arrival order.** `ADD` of a live word and `DELETE` of an absent or
  deleted word are no-ops (no new version), so the same word from two devices does not duplicate (`ux_user_dictionary_word`
  is the backstop). `ADD` of a tombstoned word revives the same row with a new version. `changedAt` is accepted but not used
  for ordering (device clocks are not trusted). There is no `REVISION_CONFLICT`: unlike typing languages this is a change
  log, not a document, so a compare-and-set would only force retries.
- **Idempotency:** `user_dictionary_applied_changes(user_id, client_change_id)` remembers applied ids for 30 days (pruned
  on push). A re-uploaded change counts as applied and does nothing, also an `ADD` that a later `DELETE` superseded.
  Rejected changes are not remembered.

### D4. Gate
Guests: 403 `GUEST_NOT_ALLOWED` (they have no entitlement, SD 15; a guest's data reaches the account through the device's
own local dictionary, and ADR 0010 handles any guest rows that exist). Then the `DICTIONARY_SYNC` entitlement (403
`FEATURE_NOT_ENTITLED`), then the `DICTIONARY_SYNC` consent through `ConsentGate` (403 `CONSENT_REQUIRED`). Withdrawing the
consent blocks pull and push and purges nothing (SD 19.3, contract); the words stay encrypted until account deletion.

### D5. Guest merge and deletion
- Merge (ADR 0010): the moved word is decrypted as the guest, its HMAC recomputed under the target's key (created if
  absent) and the word sealed for the target; the target wins on `(language, recomputed hmac)` including tombstones; moved
  entries get versions after the target's counter, which the counter then records. The guest's state and applied-change
  rows are deleted.
- Deletion (ADR 0015): entries, applied-change ids and the key row are deleted.

### D6. Placement and migration
Code is in `languages` (owns `user_dictionary_entries` and already holds the merge and deletion participants); SD §24 lists
the endpoint under preferences, but all its tables are languages'. **V13** adds `user_dictionary_sync_state` (key, key
version, counter) and `user_dictionary_applied_changes`.

## Contract
Added 400 and 401 to the pull operation, 401 to the push, and described the behaviour above in both. Schemas unchanged:
`DictionaryChange.clientChangeId` is overloaded on download (entry id).

## Consequences and open questions
- Tombstones keep the word ciphertext, so a deleted word remains (encrypted) until account deletion or a retention job; a
  purge of old tombstones (B6.3) is safe for the counter, but a device offline longer than that would miss the delete.
- Last-writer-wins by arrival can resurrect a word a device re-adds after another device deleted it; acceptable for a
  personal word list. Stricter semantics need a per-word revision in the contract.
- The server can read the HMAC key and the words (documented limit of server-side encryption, SD 19).
- Data-key rotation does not yet re-encrypt old rows (ADR 0005); the state row must be re-encrypted, never regenerated.
