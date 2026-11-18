-- B5.6 AI feedback and B5.7 AI history.
--
-- 1. ai_feedback.reason_codes: the contract (FeedbackRequest.reasonCodes) has WRONG_GENDER_OR_FORMALITY and TOO_SLOW,
--    which the V1 check did not allow. The V1 codes stay allowed.
-- 2. ai_feedback input_ciphertext / output_ciphertext: the optional input/output text a person attaches with the
--    FEEDBACK_TEXT consent (includeContent). Ciphertext only, sealed per column and row with the user data key, and
--    only together with the consent record and key version, like the comment.
-- 3. user_history_items: one history row per AI request and user, so a replayed request can never write a second one.

ALTER TABLE ai_feedback DROP CONSTRAINT ck_ai_feedback_reasons;
ALTER TABLE ai_feedback ADD CONSTRAINT ck_ai_feedback_reasons CHECK (
    reason_codes <@ ARRAY['WRONG_MEANING', 'WRONG_TONE', 'WRONG_GENDER', 'WRONG_FORMALITY', 'WRONG_GENDER_OR_FORMALITY',
                          'WRONG_SCRIPT', 'GRAMMAR', 'SPELLING', 'UNNATURAL', 'CULTURALLY_INAPPROPRIATE', 'OFFENSIVE',
                          'TOO_LONG', 'TOO_SHORT', 'TOO_SLOW', 'NAME_CHANGED', 'OTHER']::TEXT[]
);

ALTER TABLE ai_feedback
    ADD COLUMN input_ciphertext  BYTEA,
    ADD COLUMN output_ciphertext BYTEA,
    ADD COLUMN updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE ai_feedback ADD CONSTRAINT ck_ai_feedback_content_consent CHECK (
    (input_ciphertext IS NULL AND output_ciphertext IS NULL)
    OR (consent_record_id IS NOT NULL AND key_version IS NOT NULL)
);

COMMENT ON COLUMN ai_feedback.input_ciphertext IS
    'Client-supplied input text attached with the FEEDBACK_TEXT consent; ciphertext under the user data key (AAD: ai_feedback, input_ciphertext, row id).';
COMMENT ON COLUMN ai_feedback.output_ciphertext IS
    'Client-supplied output text attached with the FEEDBACK_TEXT consent; ciphertext under the user data key (AAD: ai_feedback, output_ciphertext, row id).';

CREATE UNIQUE INDEX ux_user_history_user_request
    ON user_history_items (user_id, ai_request_id)
    WHERE ai_request_id IS NOT NULL;
