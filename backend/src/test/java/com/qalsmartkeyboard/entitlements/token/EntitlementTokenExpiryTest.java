package com.qalsmartkeyboard.entitlements.token;

import static com.qalsmartkeyboard.entitlements.token.TokenFixtures.granted;
import static com.qalsmartkeyboard.entitlements.token.TokenFixtures.keys;
import static com.qalsmartkeyboard.entitlements.token.TokenFixtures.pem;
import static com.qalsmartkeyboard.entitlements.token.TokenFixtures.properties;
import static com.qalsmartkeyboard.entitlements.token.TokenFixtures.resolverReturning;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.entitlements.token.EntitlementTokenService.EntitlementToken;

/** ADR 0007 D27: a token never outlives the entitlement it states (source end plus the renewal lag). */
class EntitlementTokenExpiryTest {

	static final Instant T0 = Instant.parse("2026-11-18T08:00:00Z");
	static final Duration LAG = Duration.ofHours(24);

	final UUID userId = UUID.randomUUID();
	final EntitlementTokenProperties properties = properties("ent-2026-11", pem(), List.of());
	final EntitlementTokenKeys keys = keys(properties);

	EntitlementToken issue(Instant effectiveUntil, boolean inGrace) {
		return new EntitlementTokenService(resolverReturning(granted(userId, "PREMIUM", true, effectiveUntil, inGrace)),
				keys, properties, Clock.fixed(T0, ZoneOffset.UTC), LAG).issue(userId, null);
	}

	@Test
	void aSubscriptionEndingInTwoHoursGivesExpEqualToEndPlusLagWhenThatIsBeforeTheTtl() {
		// end + lag = 26 h is past the 24 h TTL, so the TTL wins; with a 1 h lag the cap applies
		Instant end = T0.plus(Duration.ofHours(2));
		assertThat(issue(end, false).expiresAt()).isEqualTo(T0.plus(Duration.ofHours(24)));
		EntitlementToken shortLag = new EntitlementTokenService(
				resolverReturning(granted(userId, "PREMIUM", true, end, false)), keys, properties,
				Clock.fixed(T0, ZoneOffset.UTC), Duration.ofHours(1)).issue(userId, null);
		assertThat(shortLag.expiresAt()).isEqualTo(end.plus(Duration.ofHours(1)));
		assertThat(shortLag.offlineGraceUntil()).isEqualTo(shortLag.expiresAt().plus(Duration.ofHours(72)));
	}

	@Test
	void aSourceAlreadyPastItsEndKeepsOnlyTheRemainingLag() {
		Instant end = T0.minus(Duration.ofHours(20)); // in the renewal lag: 4 h left
		EntitlementToken token = issue(end, false);
		assertThat(token.expiresAt()).isEqualTo(T0.plus(Duration.ofHours(4)));
		assertThat(token.offlineGraceUntil()).isEqualTo(token.expiresAt().plus(Duration.ofHours(72)));
	}

	@Test
	void aGraceSourceHasNoLag() {
		Instant graceEnd = T0.plus(Duration.ofHours(5));
		assertThat(issue(graceEnd, true).expiresAt()).isEqualTo(graceEnd);
	}

	@Test
	void aSourceEndingLaterThanTheTtlOrNeverKeepsTheFullTtl() {
		assertThat(issue(T0.plus(Duration.ofDays(30)), false).expiresAt()).isEqualTo(T0.plus(Duration.ofHours(24)));
		assertThat(issue(null, false).expiresAt()).isEqualTo(T0.plus(Duration.ofHours(24)));
	}

	@Test
	void theCapIsAtLeastOneSecondAfterIssue() {
		Instant end = T0.minus(LAG).plusMillis(300); // 0.3 s of lag left
		assertThat(issue(end, false).expiresAt()).isEqualTo(T0.plusSeconds(1));
	}

	@Test
	void theDefaultLagIs24Hours() {
		EntitlementToken token = new EntitlementTokenService(
				resolverReturning(granted(userId, "PREMIUM", true, T0.minus(Duration.ofHours(23)), false)), keys,
				properties, Clock.fixed(T0, ZoneOffset.UTC)).issue(userId, null);
		assertThat(token.expiresAt()).isEqualTo(T0.plus(Duration.ofHours(1)));
	}
}
