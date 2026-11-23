# ADR 0048 — Extended AI writing tools, on-device creative and productivity tools, prepaid credits

**Status:** Accepted (design amendment) · **Date:** 2026-11-23 · **Requested by:** product owner ·
**Requirements:** `Implementation_notes.md` ("the 8 best AI features to monetise" and the tools/credits/router
sections that follow it) · **Amends:** SD v2.0 §2, §3.3, §4, §6.6–6.7, §8.2, §11.2–11.3, §11.10, §14.2, §15, §17.1,
§19.2, §20.2, §21.3, §25, §26; Review §5.1 (canonical feature codes) · **Builds on:** ADR 0027 (text actions, e-mail
writer), 0031 (AI panels), 0035 (tools panel), 0041 (AI tools grid), 0014 (provider SPI), 0017 (pipeline), 0011
(metering), 0045–0047 (payments) · **Schema/contract:** no applied-migration edits (D1); new feature codes and the
`MEDIA_AI` category arrive with the forward migration that implements them; endpoints land in the OpenAPI contract
in the implementing step (plan rule 3).

## Context
The owner added a monetisation-driven feature list on top of the v2.0 baseline: twelve AI writing features, a Draw &
Sketch engine, four creative tools, five productivity utilities, prepaid AI credit packages and an explicit
requirement that the **platform** picks the AI model per task. Most of the writing features already existed
(grammar, translation, tone, smart replies, rewrite/summarise, voice-to-text, e-mail drafting); this ADR records
how the rest fits the design without disturbing the applied baseline or any cross-referenced section number.

## Decisions

### 1. Extended writing tools ride the existing pipeline — new catalogue codes, not new machinery
- `FORMAT_TEXT` on `/ai/transform` (`options.format`: BULLETS / NUMBERED_LIST / PARAGRAPHS / REPORT /
  MEETING_NOTES). A transform, not a new endpoint.
- `SMART_REPLY` gains `intent` (AGREE, DISAGREE_POLITELY, ASK_CLARIFICATION, ACCEPT_INVITATION, DECLINE_POLITELY,
  THANK_SENDER); free `instructions` stay. Intents are template keys — user text remains data (SD §11.4).
- `TONE_ADJUST` gains ROMANTIC, CONFIDENT, FUNNY, PERSUASIVE; `DRAFT_DOCUMENT` gains JOB_APPLICATION, CV,
  COVER_LETTER, BUSINESS_MESSAGE. Same metering, additive enums.
- New endpoints, each metered per request through reserve → settle: `POST /ai/chat` (`AI_CHAT`, **stateless** —
  the client sends the visible turns, the server stores none of them), `POST /ai/social-posts` (`SOCIAL_POST`),
  `POST /ai/form-assist` (`FORM_ASSIST`), `POST /ai/ocr` (`OCR_SCAN`, PRINTED/HANDWRITING), `POST /ai/images`
  (P3 `MEDIA_AI` tasks: SKETCH_ENHANCE, DOODLE_TO_IMAGE, AI_STICKER_GEN, AI_IMAGE_GEN).
- **R-7 (new product rule):** FORM_ASSIST/AI_CHAT/EXPLAIN_TEXT results carry a verify-before-relying notice; legal,
  immigration, medical and financial topics add an explicit not-professional-advice disclaimer. This is a product
  requirement verbatim from the notes ("the user must verify all answers").
- Received-message translation stays the AI Assist select/copy/share pattern (J7); the clipboard is read only
  inside an action's tap handler, never monitored (the notes' "paste from the last clipboard text" is the existing
  Assist flow, ADR 0031 §2).

### 2. Task-based model routing is backend-owned and data-configured
SD §11.2 now states it normatively: the platform selects the model from feature code + context; users never see or
configure models; the mapping lives in `ai_language_capabilities` × `ai_routing_policies` so it changes without an
app release; the admin console manages it. Route order: data-policy (hard) → task → language pair (validated for
that pair beats cheaper) → complexity → quality tier → unit cost → health. The notes' example table
(grammar → economical, translation → best validated pair model, cover letter/CV → advanced) is recorded as the
illustrative mapping.

### 3. Creative and productivity tools are on-device first; the server sees them only through explicit AI calls
- **R-8 (new product rule):** user-created media lives only on the device; sharing is an explicit act through the
  host app (`commitContent` rich insert where the field accepts the MIME type, else share sheet) or the gallery.
  No server tables in v1 (SD §20.2); optional later sync must use `user_data_keys`-encrypted rows like history.
- Draw & Sketch phases map to the notes verbatim: P1 canvas/pen/colours/eraser/undo-redo/PNG/share (free); P2
  gallery, text/shapes, more brushes, optional encrypted cloud backup; P3 SKETCH_ENHANCE, DOODLE_TO_IMAGE,
  HANDWRITING_RECOGNITION via the gateway and credits.
- Stickers (WebP 512×512 + on-device strip), GIFs (animated WebP preferred), cards and the annotator are
  companion/keyboard tools whose basic tier is free; `STICKER_MAKER`, `GIF_MAKER`, `CARD_MAKER`,
  `IMAGE_ANNOTATOR`, `QR_GENERATE` are PLATFORM boolean entitlements for the premium tier only.
- Deterministic utilities (calculator, calendar converter — already in `keyboard-core`, unit converter, QR encode)
  run locally and unmetered. Currency conversion uses a daily `GET /tools/fx-rates` snapshot with a staleness note;
  on-device OCR is free, `OCR_SCAN` adds Ethiopic/handwriting/layouts metered per request.

### 4. Prepaid credit packs are a first-class purchase (§15.6)
`AI_CREDITS`-kind `billing_products` sold through the same §16 order → verify → `ai_credit_lots` pipeline: no
subscription required, no mandate, local rails in Ethiopia, store billing where policy requires; suggested
Starter/Standard/Pro packages (100/500/1,500 credits) are provisional data. Consumption order is unchanged
(plan allowance first, then credits).

### 5. Document mechanics
- No section renumbering: 60+ ADR references and the contract cite §13–§26 numbers, so new material was added as
  §6.6–6.7, §11.10, §15.6 and appended table rows only.
- Review §5.1 gains the 16 new canonical codes, including the new `MEDIA_AI` category (CHECK extended by a forward
  migration).
- Provisional quotas for the new features were added to SD §15.2 under the same PROVISIONAL_V2 caveat.

## Consequences / open
- OpenAPI contract additions (`/ai/chat`, `/ai/social-posts`, `/ai/form-assist`, `/ai/ocr`, `/ai/images`,
  `/tools/fx-rates`, the new enum values and `intent`) land with their implementation steps, per plan rule 3.
- Open questions added to SD §26.2: host-app `commitContent` coverage, FX source/licence, Ethiopic OCR coverage,
  GIF encoder licensing, media-AI provider selection, credit-pack pricing.
- Phase 8 (`T8.x`) added to the implementation plan.
