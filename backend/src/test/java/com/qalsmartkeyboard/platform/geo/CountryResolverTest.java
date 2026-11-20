package com.qalsmartkeyboard.platform.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.qalsmartkeyboard.platform.geo.CountryResolution.Confidence;
import com.qalsmartkeyboard.platform.geo.CountryResolution.Flag;
import com.qalsmartkeyboard.platform.geo.CountryResolution.Source;
import com.qalsmartkeyboard.platform.geo.CountryResolver.Signals;

/** ADR 0045 country-detection matrix. */
class CountryResolverTest {

	static final String EDGE = "173.245.48.10";
	static final String SECRET = "edge-secret-0123456789-abcdefgh";

	static CountryResolver resolver(List<String> cidrs, String secret, String devMock) {
		return new CountryResolver(new GeoProperties(cidrs, secret, null, devMock));
	}

	static final CountryResolver CIDR_ONLY = resolver(List.of("173.245.48.0/20", "2400:cb00::/32"), null, null);
	static final CountryResolver BOTH = resolver(List.of("173.245.48.0/20"), SECRET, null);
	static final CountryResolver NONE = resolver(List.of(), null, null);

	static Signals s(String peer, String cf, String secret, String network, String sim) {
		return new Signals(peer, cf, secret, network, sim);
	}

	@Test
	void trustedEdgeCountryConfirmedByAHintIsHigh() {
		CountryResolution r = CIDR_ONLY.resolve(s(EDGE, "ET", null, "et", null));
		assertThat(r.country()).isEqualTo("ET");
		assertThat(r.source()).isEqualTo(Source.IP_EDGE);
		assertThat(r.confidence()).isEqualTo(Confidence.HIGH);
		assertThat(r.flags()).isEmpty();
		assertThat(r.networkCountry()).isEqualTo("ET");
	}

	@Test
	void trustedEdgeCountryWithoutHintsIsMedium() {
		CountryResolution r = CIDR_ONLY.resolve(s(EDGE, "et", null, null, null));
		assertThat(r.country()).isEqualTo("ET");
		assertThat(r.confidence()).isEqualTo(Confidence.MEDIUM);
	}

	@Test
	void ipv6EdgeIsTrustedToo() {
		assertThat(CIDR_ONLY.resolve(s("2400:cb00:0:0:0:0:0:1", "ET", null, null, null)).ipTrusted()).isTrue();
	}

	@Test
	void cfHeaderFromAnUntrustedPeerIsIgnored() {
		CountryResolution r = CIDR_ONLY.resolve(s("203.0.113.9", "ET", null, null, null));
		assertThat(r.country()).isNull();
		assertThat(r.source()).isEqualTo(Source.NONE);
		assertThat(r.ipTrusted()).isFalse();
		assertThat(r.flags()).containsExactly(Flag.IP_UNTRUSTED);
	}

	@Test
	void withoutAnyEdgeConfigurationNothingIsTrusted() {
		CountryResolution r = NONE.resolve(s(EDGE, "ET", SECRET, null, null));
		assertThat(r.country()).isNull();
		assertThat(r.has(Flag.IP_UNTRUSTED)).isTrue();
		assertThat(NONE.edgeTrustConfigured()).isFalse();
	}

	@Test
	void everyConfiguredCheckMustPass() {
		assertThat(BOTH.resolve(s(EDGE, "ET", SECRET, null, null)).country()).isEqualTo("ET");
		assertThat(BOTH.resolve(s(EDGE, "ET", "wrong", null, null)).country()).isNull();
		assertThat(BOTH.resolve(s(EDGE, "ET", null, null, null)).country()).isNull();
		assertThat(BOTH.resolve(s("203.0.113.9", "ET", SECRET, null, null)).country()).isNull();
		CountryResolver secretOnly = resolver(List.of(), SECRET, null);
		assertThat(secretOnly.resolve(s("203.0.113.9", "ET", SECRET, null, null)).country()).isEqualTo("ET");
	}

	@Test
	void unknownAndTorFromTheEdgeMeanNoIpCountry() {
		CountryResolution xx = CIDR_ONLY.resolve(s(EDGE, "XX", null, null, null));
		assertThat(xx.country()).isNull();
		assertThat(xx.flags()).containsExactly(Flag.IP_UNKNOWN);
		CountryResolution absent = CIDR_ONLY.resolve(s(EDGE, null, null, null, null));
		assertThat(absent.flags()).containsExactly(Flag.IP_UNKNOWN);
		CountryResolution tor = CIDR_ONLY.resolve(s(EDGE, "T1", null, "ET", "ET"));
		assertThat(tor.has(Flag.ANONYMIZER)).isTrue();
		assertThat(tor.ipCountry()).isNull();
		// no IP country: the agreeing hints decide, with LOW confidence (never enough to unlock a country by default)
		assertThat(tor.country()).isEqualTo("ET");
		assertThat(tor.source()).isEqualTo(Source.CLIENT_HINTS);
		assertThat(tor.confidence()).isEqualTo(Confidence.LOW);
	}

	@Test
	void hintsAloneDecideOnlyWhenTheyAgree() {
		CountryResolution agree = NONE.resolve(s(null, null, null, "ET", "et"));
		assertThat(agree.country()).isEqualTo("ET");
		assertThat(agree.source()).isEqualTo(Source.CLIENT_HINTS);
		assertThat(agree.confidence()).isEqualTo(Confidence.LOW);

		CountryResolution disagree = NONE.resolve(s(null, null, null, "ET", "KE"));
		assertThat(disagree.country()).isNull();
		assertThat(disagree.flags()).containsExactly(Flag.NETWORK_SIM_MISMATCH);

		CountryResolution wifiOnly = NONE.resolve(s(null, null, null, null, "ET"));
		assertThat(wifiOnly.country()).isNull();
		assertThat(wifiOnly.confidence()).isEqualTo(Confidence.NONE);
	}

	@Test
	void singleMismatchIsNeverVpn() {
		CountryResolution travel = CIDR_ONLY.resolve(s(EDGE, "US", null, "US", "ET"));
		assertThat(travel.country()).isEqualTo("US");
		assertThat(travel.confidence()).isEqualTo(Confidence.HIGH);
		assertThat(travel.flags()).containsExactlyInAnyOrder(Flag.IP_SIM_MISMATCH, Flag.NETWORK_SIM_MISMATCH);
		assertThat(travel.has(Flag.POSSIBLE_VPN_OR_PROXY)).isFalse();

		CountryResolution simOnly = CIDR_ONLY.resolve(s(EDGE, "DE", null, null, "ET"));
		assertThat(simOnly.flags()).containsExactly(Flag.IP_SIM_MISMATCH);
		assertThat(simOnly.confidence()).isEqualTo(Confidence.LOW);
	}

	@Test
	void ipDifferingFromAgreeingHintsIsPossibleVpnAndTheIpStillDecides() {
		CountryResolution r = CIDR_ONLY.resolve(s(EDGE, "US", null, "ET", "ET"));
		assertThat(r.country()).isEqualTo("US");
		assertThat(r.confidence()).isEqualTo(Confidence.LOW);
		assertThat(r.flags()).containsExactlyInAnyOrder(Flag.IP_SIM_MISMATCH, Flag.IP_NETWORK_MISMATCH,
				Flag.POSSIBLE_VPN_OR_PROXY);
	}

	@Test
	void invalidHintsAreIgnoredAndFlagged() {
		CountryResolution r = CIDR_ONLY.resolve(s(EDGE, "ET", null, "Ethiopia", "zz"));
		assertThat(r.networkCountry()).isNull();
		assertThat(r.simCountry()).isNull();
		assertThat(r.flags()).containsExactly(Flag.INVALID_HINT);
		assertThat(r.confidence()).isEqualTo(Confidence.MEDIUM);
		assertThat(CIDR_ONLY.resolve(s(EDGE, "ET", null, " ", "")).flags()).isEmpty();
	}

	@Test
	void devMockCountryReplacesTheIpCountry() {
		CountryResolver dev = resolver(List.of(), null, "et");
		CountryResolution r = dev.resolve(s("127.0.0.1", "US", null, "KE", null));
		assertThat(r.country()).isEqualTo("ET");
		assertThat(r.source()).isEqualTo(Source.DEV_MOCK);
		assertThat(r.confidence()).isEqualTo(Confidence.HIGH);
		assertThat(r.has(Flag.IP_NETWORK_MISMATCH)).isTrue();
		assertThat(dev.devMockActive()).isTrue();
	}

	@Test
	void devMockCountryIsRefusedUnderProductionProfilesAndMustBeValid() {
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");
		assertThatThrownBy(() -> new GeoConfiguration().countryResolver(new GeoProperties(List.of(), null, null, "ET"),
				prod)).isInstanceOf(IllegalStateException.class).hasMessageContaining("DEV_MOCK_COUNTRY");
		MockEnvironment dev = new MockEnvironment();
		assertThat(new GeoConfiguration().countryResolver(new GeoProperties(List.of(), null, null, "ET"), dev)
				.devMockActive()).isTrue();
		assertThatThrownBy(() -> resolver(List.of(), null, "Ethiopia")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> resolver(List.of("not-a-cidr"), null, null))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void summaryNeverContainsAnAddress() {
		CountryResolution r = CIDR_ONLY.resolve(s(EDGE, "ET", null, "ET", "ET"));
		assertThat(r.summary().toString()).doesNotContain(EDGE).contains("IP_EDGE", "HIGH");
		assertThat(new GeoProperties(List.of(), SECRET, null, null).toString()).doesNotContain(SECRET);
	}
}
