package com.qalsmartkeyboard.identity.security;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.qalsmartkeyboard.identity.AuthenticatedUser;

/**
 * Authentication from a verified access token. The principal is {@link AuthenticatedUser}; authorities are
 * {@code ROLE_REGISTERED} or {@code ROLE_GUEST}. The token itself is not kept (nothing to leak through
 * {@code toString} or the security context).
 */
final class AccessTokenAuthentication extends AbstractAuthenticationToken {

	private final AuthenticatedUser principal;

	AccessTokenAuthentication(AuthenticatedUser principal) {
		super(List.of(new SimpleGrantedAuthority("ROLE_" + principal.accountType())));
		this.principal = principal;
		setAuthenticated(true);
	}

	@Override
	public Object getCredentials() {
		return null;
	}

	@Override
	public AuthenticatedUser getPrincipal() {
		return principal;
	}

	@Override
	public String getName() {
		return principal.userId().toString();
	}
}
