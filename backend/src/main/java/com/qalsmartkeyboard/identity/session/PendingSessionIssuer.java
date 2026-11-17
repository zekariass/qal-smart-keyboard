package com.qalsmartkeyboard.identity.session;

import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.account.UserAccount;

/**
 * Placeholder until access/refresh tokens exist (plan step B2.4). Throws, so a successful OTP verify rolls back
 * (the challenge stays usable) instead of signing anyone in without a session.
 */
@Component
class PendingSessionIssuer implements SessionIssuer {

	@Override
	public IssuedSession issue(UserAccount user, SessionContext context) {
		throw new IllegalStateException("Session issuance is not implemented yet (plan step B2.4)");
	}
}
