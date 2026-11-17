-- B2.1: phone OTP needs the number again when a code is verified.
--
-- otp_challenges (V1) stores the number only as a keyed HMAC (phone_hash), and the
-- contract's OtpVerifyRequest carries only challengeId + code. A successful verify
-- must find or create the account by E.164 number (users.phone_e164,
-- user_auth_identities.provider_subject), which a one-way hash cannot give back.
--
-- phone_ciphertext holds the number sealed with AES-256-GCM under a service key
-- derived from qal.identity.otp.secret-key, with the challenge id as associated
-- data (format 0x01 || nonce(12) || ciphertext || tag(16)). The number is never
-- stored in clear here; rows are short-lived like the rest of the challenge.
--
-- Nothing wrote otp_challenges before this migration (no OTP code existed), and a
-- challenge lives for minutes, so any rows are dropped instead of back-filled.

DELETE FROM otp_challenges;

ALTER TABLE otp_challenges
    ADD COLUMN phone_ciphertext BYTEA NOT NULL;

ALTER TABLE otp_challenges
    ADD CONSTRAINT ck_otp_phone_ciphertext
        CHECK (octet_length(phone_ciphertext) BETWEEN 30 AND 64);
