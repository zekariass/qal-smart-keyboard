# ADR 0021 — AI feedback and opt-in translation history

**Status:** Accepted · **Date:** 2026-11-18 · **Plan steps:** B5.6, B5.7 (depend on B5.5, B1.5) · **Spec:** SD 11.7 (history: opt-in,
encrypted, 1-365 days default 30, favourites), 11.8 (feedback), 12 and 19.3 (consents), 19.2 (retention); contract
`submitAiFeedback`, `listHistory`, `getHistoryItem`, `updateHistoryItem`, `deleteHistoryItem`, `clearHistory`; ADR 0005
(encryption contexts), 0010 (guest merge), 0015 (deletion), 0017 (pipeline, replay cache), 0020 (retention).

## Context
B5.5 accepted and ignored `saveToHistory`; V1 has `ai_feedback` and `user_history_items`; B3.3 stores the preference
(`ai_history_enabled`, `history_retention_days`), B3.5 the `SAVE_AI_HISTORY` / `FEEDBACK_TEXT` consents, B6.3 the purge of
expired history. This ADR fixes the rules the code follows.

## Decisions

### Feedback
1. **Wire values follow the contract**, not the SD wording: `rating` `POSITIVE` / `NEGATIVE` (stored as +1 / -1), reason codes
   from `FeedbackRequest` (which has `WRONG_GENDER_OR_FORMALITY` and `TOO_SLOW`; V17 widens the V1 CHECK, the old codes stay).
2. **Target:** a `SUCCEEDED` `ai_requests` row of the caller; anything else is 404 (no oracle for other users' ids).
3. **One rating per (request, user), replace on resend** (the unique key already exists). 409 would force the client to track
   whether it already sent one; a changed mind is normal for thumbs. A resend replaces rating, reasons, comment and content.
4. **Consent:** `comment`, and `includeContent` (with `inputText` / `outputText`), need a granted `FEEDBACK_TEXT`. Without it the
   whole request is 403 `CONSENT_REQUIRED` and nothing is stored: dropping the words silently would record a rating the
   person qualified with text they believed was sent. Without `includeContent` the texts are ignored (never stored).
5. **Storage:** each text is sealed with the user's data key, context `(ai_feedback, <column>, row id)`; V17 adds
   `input_ciphertext` / `output_ciphertext` (the V1 table only had the comment) with a CHECK that ciphertext needs the consent
   record and key version. `consent_record_id` = the grant record at the time (V1 composite FK).
6. 60/min per user; body read by hand so a bad value never reaches a log (as `AiRequestParser`).
7. Retention of `ai_feedback` is not defined by SD 19.2 and not added here; it is deleted with the account (ADR 0015).
   Withdrawing `FEEDBACK_TEXT` stops new text; stored text stays until the account goes (open question below).

### History
8. **Off by default; all gates must hold, each read fresh at write time:** the request said `saveToHistory: true` (contract
   default false), `user_preferences.ai_history_enabled`, a granted `SAVE_AI_HISTORY`, and the `HISTORY_SYNC` entitlement
   (SD 11.7: both access and consent; guests have none). A missing preferences row means off.
9. **When:** after a settled success of the request that reserved its key (not for failures, not for a replay answered from the
   cache, not for an uncharged recompute), before the replay cache stores the response so a replay returns the same
   `historyItemId`. A partial unique index `(user_id, ai_request_id)` (V17) makes a duplicate impossible. A sink failure is
   logged (class only) and counted; the AI answer is unaffected. Only TRANSLATE and the transform tasks are offered (detection
   has no text result worth keeping).
10. **Module boundary:** `aigateway` defines `AiHistorySink`; `personalization` implements it (and owns the endpoints), so
    `aigateway` does not depend on `personalization`.
11. **Encryption:** `input_ciphertext` and `output_ciphertext` are sealed separately, context `(user_history_items, <column>,
    row id)`, one `key_version` per row (re-sealed if the key rotates in between). No plaintext AI text is ever written to
    PostgreSQL (SD amended in ADR 0007 D35).
12. **Retention:** `expires_at` = now + `user_preferences.history_retention_days` (1-365, default 30, read at write time).
    Favourites have none (V1 CHECK). Reads hide expired items immediately; the B6.3 job deletes them. Unmarking a favourite
    sets a new expiry of the current retention from now (not from creation, which could expire it at once).
13. **Endpoints:** list (newest first, keyset cursor `(created_at, id)`, filters `favorite`, `featureCode`), get, patch favourite,
    delete one, delete all (favourites included). All work whatever the preference or consent is: a person who switched
    history off can still review and delete. PATCH is parsed by hand (a binding error would quote the value).
14. **Consent withdrawal** stops new writes (preference switched off by B3.5; the recorder re-checks the consent). Existing items
    stay: the contract says the withdrawal "offers deletion", which is the client's prompt plus `DELETE /me/history`;
    the server does not delete on its own (SD 19.3).
15. **Deletion and merge** need no change: `PrivacyDeletion` deletes both tables; `PrivacyGuestMerge` already re-encrypts
    history rows with the same per-column contexts for the target; `ai_feedback` is not moved (shredded with the guest).

## Consequences
- V17 is the only migration; no new table (101 tables).
- A client must send `saveToHistory: true` when the user has history on; a client that omits it gets no history.

## Open
- Whether withdrawing `FEEDBACK_TEXT` should also delete stored feedback comments/content (needs a decision; today they stay
  until account deletion).
- No retention period for `ai_feedback` in SD 19.2.
- A `HISTORY_SYNC`-less plan that enables the preference silently keeps nothing; the client could read the entitlement.
