-- B3.3: optimistic-concurrency revisions for the synced preference documents (System Design 10.1, contract `revision`).
--
-- user_preferences is one row per user, so the revision is a column. The row exists only after the first write; a client that
-- has never synced reads revision 0 and writes with revision 0 to create it. Existing rows (none are written before B3.3)
-- start at 1.
ALTER TABLE user_preferences
    ADD COLUMN revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1);

-- /me/typing-languages is a list of rows (user_typing_languages), so its revision needs a row of its own that also exists
-- when the list is empty. It is created at revision 0 by the first write and bumped with UPDATE ... WHERE revision = ?, which
-- also serialises concurrent writers of one user.
CREATE TABLE user_typing_language_revisions (
    user_id     UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    revision    INTEGER NOT NULL CHECK (revision >= 0),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
