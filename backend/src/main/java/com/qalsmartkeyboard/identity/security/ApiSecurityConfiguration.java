package com.qalsmartkeyboard.identity.security;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.identity.token.TokenConfiguration;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ProblemResponseWriter;

import jakarta.servlet.DispatcherType;

/**
 * The application's only {@link SecurityFilterChain} (OI-P11): a stateless resource server for access tokens.
 *
 * <ul>
 * <li>No HTTP session, no CSRF (no cookies; every call carries a bearer token), no form/basic login, no request
 * cache.</li>
 * <li>Anonymous access exactly where the contract's operation {@code security} allows it ({@link ApiPaths}); every
 * other request (including unknown paths) needs a valid ES256 access token.</li>
 * <li>401 and 403 are contract Problems with correlation id ({@link ApiAuthenticationEntryPoint}, access-denied
 * handler) written by the platform's {@link ProblemResponseWriter}.</li>
 * </ul>
 * The access token is verified statelessly (signature, claims, expiry); see {@code AuthenticatedUser} for the
 * revocation window this implies. Guest tokens are the exception: see {@link #authenticate}. The app-version gate
 * (B1.6) is an MVC interceptor and therefore runs after authentication.
 */
@Configuration(proxyBeanMethods = false)
@Import(TokenConfiguration.class)
public class ApiSecurityConfiguration {

	@Bean
	SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JwtDecoder accessTokenDecoder,
			ProblemResponseWriter problems, ObjectProvider<UserAccountStore> accounts) throws Exception {
		ApiAuthenticationEntryPoint entryPoint = new ApiAuthenticationEntryPoint(problems);
		AccessDeniedHandler accessDenied = accessDeniedHandler(problems);
		return http.csrf(csrf -> csrf.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(b -> b.disable()).formLogin(f -> f.disable()).logout(l -> l.disable())
				.requestCache(c -> c.disable())
				.authorizeHttpRequests(a -> a.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers(ApiPaths.PARTNER).denyAll()
						.requestMatchers(ApiPaths.OPTIONAL_BEARER, ApiPaths.NO_BEARER).permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(o -> o.bearerTokenResolver(bearerTokenResolver())
						.jwt(j -> j.decoder(accessTokenDecoder)
								.jwtAuthenticationConverter(jwt -> authenticate(jwt, accounts.getIfAvailable())))
						.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDenied))
				.exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDenied))
				.build();
	}

	/**
	 * The authentication of a verified access token, with one primary-key lookup of the account:
	 * <ul>
	 * <li>A <b>guest</b> token is honoured only while its account is an active guest, or a guest whose deletion is
	 * pending (B2.6/ADR 0010, ADR 0015): once the guest is merged into or upgraded to a registered account (or suspended
	 * or deleted), its remaining access tokens are refused at once rather than at expiry
	 * ({@code GUEST_SESSION_INVALID}), so they can neither act on the merged guest nor trigger a second merge.</li>
	 * <li>A <b>registered</b> token is refused ({@code UNAUTHENTICATED}, {@code invalid_token}) once its account is
	 * deleted (from the moment the deletion executor starts, B6.2/ADR 0015) or merged, so nothing can write rows for a
	 * deleted account after its data was purged. Otherwise registered tokens are stateless (a revoked session's access
	 * token lives out its 15 minutes, see {@code AuthenticatedUser}); a token whose account row does not exist is left
	 * to the endpoint (it never resolves to any data).</li>
	 * </ul>
	 * Without the account store (MVC slice tests only) guest tokens are refused (fail closed) and registered tokens pass.
	 */
	static AccessTokenAuthentication authenticate(Jwt jwt, UserAccountStore accounts) {
		AuthenticatedUser principal = AccessTokenService.principal(jwt);
		UserAccountStore.AccountState state = accounts == null ? null
				: accounts.state(principal.userId()).orElse(null);
		if (principal.isGuest()) {
			if (state == null || !state.acceptsGuestToken()) {
				throw new GuestSessionInvalidException();
			}
		}
		else if (state != null && state.isClosed()) {
			throw new InvalidBearerTokenException("The account is closed");
		}
		return new AccessTokenAuthentication(principal);
	}

	/** 403 {@code FORBIDDEN} Problem for an authenticated caller that the chain does not allow. */
	static AccessDeniedHandler accessDeniedHandler(ProblemResponseWriter problems) {
		return (request, response, denied) -> problems.write(request, response,
				new ApiException(ApiErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, null, List.of(), Map.of()));
	}

	/** Bearer tokens from the {@code Authorization} header only, and never on the no-bearer and partner paths. */
	static BearerTokenResolver bearerTokenResolver() {
		DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
		return request -> ApiPaths.NO_BEARER.matches(request) || ApiPaths.PARTNER.matches(request) ? null
				: header.resolve(request);
	}
}
