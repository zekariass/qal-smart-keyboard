package com.qalsmartkeyboard.identity.session;

import com.qalsmartkeyboard.identity.account.UserAccount;

/**
 * Starts a new session (a new refresh-token family) for a user who has just proved an identity. Runs inside the
 * caller's transaction, so the proof (for example a consumed OTP challenge) and the session commit together.
 */
public interface SessionIssuer {

	IssuedSession issue(UserAccount user, SessionContext context);
}
