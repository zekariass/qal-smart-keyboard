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
	 * The authentication of a verified access token. A <b>guest</b> token is honoured only while its account is still
	 * an active guest (one primary-key lookup per guest request, B2.6/ADR 0010): once the guest is merged into or
	 * upgraded to a registered account (or suspended or deleted), its remaining access tokens are refused at once rather
	 * than at expiry, so they can neither act on the merged guest nor trigger a second merge. Registered tokens stay
	 * fully stateless (their revocation window is the 15 minute lifetime, see {@code AuthenticatedUser}). Without the
	 * account store (MVC slice tests only) guest tokens are refused: fail closed.
	 */
	static AccessTokenAuthentication authenticate(Jwt jwt, UserAccountStore accounts) {
		AuthenticatedUser principal = AccessTokenService.principal(jwt);
		if (principal.isGuest() && (accounts == null || !accounts.isActiveGuest(principal.userId()))) {
			throw new InvalidBearerTokenException("The guest account is no longer active");
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
