package com.qalsmartkeyboard.platform.geo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import com.qalsmartkeyboard.platform.geo.CountryResolution.Confidence;
import com.qalsmartkeyboard.platform.geo.CountryResolution.Flag;
import com.qalsmartkeyboard.platform.geo.CountryResolution.Source;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the billing country of a request (ADR 0045 "Country detection").
 *
 * <ol>
 * <li><b>IP country (primary).</b> {@code CF-IPCountry} counts only when the request came through the configured edge:
 * the direct peer ({@link HttpServletRequest#getRemoteAddr()}) is inside {@code qal.geo.trusted-proxies} and/or the
 * edge's shared secret header matches, every configured check passing and at least one configured. Default: not
 * trusted, so local development and unknown edges cannot spoof it. {@code XX} (unknown) and a missing header from a
 * trusted edge mean "no IP country" ({@link Flag#IP_UNKNOWN}); {@code T1} (Tor) likewise, flagged
 * {@link Flag#ANONYMIZER}.</li>
 * <li><b>Development override.</b> {@code DEV_MOCK_COUNTRY} replaces the IP country (source {@link Source#DEV_MOCK});
 * the prod/production/staging profiles refuse to start with it set ({@link GeoConfiguration}).</li>
 * <li><b>Client hints (supporting).</b> {@code X-Qal-Network-Country} and {@code X-Qal-Sim-Country}, normalised to upper
 * case and accepted only when they are ISO 3166-1 alpha-2 codes. They raise or lower the confidence of a trusted IP
 * country and set mismatch flags; {@link Flag#POSSIBLE_VPN_OR_PROXY} needs both hints to agree with each other and
 * differ from the IP country (a single mismatch is travel or roaming as often as anything else).</li>
 * <li><b>No trusted IP country.</b> The hints decide only when both are present and agree (source
 * {@link Source#CLIENT_HINTS}, confidence {@link Confidence#LOW}); otherwise the country is unknown. LOW never unlocks
 * a country-specific payment provider with the default billing configuration.</li>
 * </ol>
 * Confidence: DEV_MOCK and an IP country confirmed by a hint are HIGH, an IP country without hints is MEDIUM, an IP
 * country contradicted by every hint is LOW.
 */
public class CountryResolver {

	public static final String CF_IP_COUNTRY = "CF-IPCountry";
	public static final String NETWORK_COUNTRY = "X-Qal-Network-Country";
	public static final String SIM_COUNTRY = "X-Qal-Sim-Country";

	private static final Logger log = LoggerFactory.getLogger(CountryResolver.class);
	private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

	/** What the resolver reads from a request; the peer address is used for the trust check only. */
	record Signals(String peerAddress, String cfIpCountry, String edgeSecret, String networkHint, String simHint) {
	}

	private final List<IpAddressMatcher> trustedProxies;
	private final byte[] edgeSecret;
	private final String edgeSecretHeader;
	private final String devMockCountry;

	CountryResolver(GeoProperties properties) {
		Objects.requireNonNull(properties, "properties");
		this.trustedProxies = properties.trustedProxies().stream().map(CountryResolver::matcher).toList();
		this.edgeSecret = properties.edgeSecret() == null ? null
				: properties.edgeSecret().getBytes(StandardCharsets.UTF_8);
		this.edgeSecretHeader = properties.edgeSecretHeader();
		if (properties.devMockCountry() != null) {
			String mock = normalise(properties.devMockCountry());
			if (mock == null) {
				throw new IllegalStateException(
						"qal.geo.dev-mock-country (DEV_MOCK_COUNTRY) must be an ISO 3166-1 alpha-2 country code");
			}
			this.devMockCountry = mock;
		}
		else {
			this.devMockCountry = null;
		}
	}

	private static IpAddressMatcher matcher(String cidr) {
		try {
			return new IpAddressMatcher(cidr);
		}
		catch (IllegalArgumentException e) {
			throw new IllegalStateException("qal.geo.trusted-proxies contains an invalid address or CIDR");
		}
	}

	/** Whether {@code CF-IPCountry} can ever be trusted with this configuration. */
	public boolean edgeTrustConfigured() {
		return !trustedProxies.isEmpty() || edgeSecret != null;
	}

	/** Whether {@code DEV_MOCK_COUNTRY} is in effect. */
	public boolean devMockActive() {
		return devMockCountry != null;
	}

	/** Resolves the billing country of {@code request}. Logs the outcome only (never an address or raw header). */
	public CountryResolution resolve(HttpServletRequest request) {
		CountryResolution resolution = resolve(new Signals(request.getRemoteAddr(), request.getHeader(CF_IP_COUNTRY),
				request.getHeader(edgeSecretHeader), request.getHeader(NETWORK_COUNTRY), request.getHeader(SIM_COUNTRY)));
		if (log.isDebugEnabled()) {
			log.debug("billing country resolved country={} source={} confidence={} flags={}", resolution.country(),
					resolution.source(), resolution.confidence(), resolution.flags());
		}
		return resolution;
	}

	CountryResolution resolve(Signals signals) {
		EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
		boolean trusted = trusted(signals);
		String ipCountry = null;
		Source source = Source.NONE;
		if (devMockCountry != null) {
			ipCountry = devMockCountry;
			trusted = true;
			source = Source.DEV_MOCK;
		}
		else {
			String raw = signals.cfIpCountry() == null ? null : signals.cfIpCountry().strip().toUpperCase(Locale.ROOT);
			if (raw != null && raw.isEmpty()) {
				raw = null;
			}
			if (raw != null && !trusted) {
				flags.add(Flag.IP_UNTRUSTED);
			}
			else if (trusted) {
				if (raw == null || "XX".equals(raw)) {
					flags.add(Flag.IP_UNKNOWN);
				}
				else if ("T1".equals(raw)) {
					flags.add(Flag.ANONYMIZER);
				}
				else if (ISO_COUNTRIES.contains(raw)) {
					ipCountry = raw;
					source = Source.IP_EDGE;
				}
				else {
					flags.add(Flag.IP_UNKNOWN);
				}
			}
		}
		String network = hint(signals.networkHint(), flags);
		String sim = hint(signals.simHint(), flags);
		if (ipCountry != null && sim != null && !sim.equals(ipCountry)) {
			flags.add(Flag.IP_SIM_MISMATCH);
		}
		if (ipCountry != null && network != null && !network.equals(ipCountry)) {
			flags.add(Flag.IP_NETWORK_MISMATCH);
		}
		if (network != null && sim != null && !network.equals(sim)) {
			flags.add(Flag.NETWORK_SIM_MISMATCH);
		}
		if (ipCountry != null && network != null && network.equals(sim) && !network.equals(ipCountry)) {
			flags.add(Flag.POSSIBLE_VPN_OR_PROXY);
		}

		String country;
		Confidence confidence;
		if (source == Source.DEV_MOCK) {
			country = ipCountry;
			confidence = Confidence.HIGH;
		}
		else if (ipCountry != null) {
			country = ipCountry;
			if (ipCountry.equals(network) || ipCountry.equals(sim)) {
				confidence = Confidence.HIGH;
			}
			else if (network == null && sim == null) {
				confidence = Confidence.MEDIUM;
			}
			else {
				confidence = Confidence.LOW;
			}
		}
		else if (network != null && network.equals(sim)) {
			country = network;
			source = Source.CLIENT_HINTS;
			confidence = Confidence.LOW;
		}
		else {
			country = null;
			source = Source.NONE;
			confidence = Confidence.NONE;
		}
		return new CountryResolution(country, source, confidence, ipCountry, trusted, network, sim, flags);
	}

	private boolean trusted(Signals signals) {
		if (!edgeTrustConfigured()) {
			return false;
		}
		if (!trustedProxies.isEmpty()) {
			String peer = signals.peerAddress();
			if (peer == null || peer.isBlank()) {
				return false;
			}
			boolean inside = false;
			for (IpAddressMatcher m : trustedProxies) {
				try {
					if (m.matches(peer)) {
						inside = true;
						break;
					}
				}
				catch (IllegalArgumentException e) {
					return false; // not an IP literal
				}
			}
			if (!inside) {
				return false;
			}
		}
		if (edgeSecret != null) {
			String presented = signals.edgeSecret();
			if (presented == null) {
				return false;
			}
			return MessageDigest.isEqual(edgeSecret, presented.getBytes(StandardCharsets.UTF_8));
		}
		return true;
	}

	private static String hint(String raw, Set<Flag> flags) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String value = normalise(raw);
		if (value == null) {
			flags.add(Flag.INVALID_HINT);
		}
		return value;
	}

	/** Upper-case ISO 3166-1 alpha-2, or {@code null}. */
	static String normalise(String raw) {
		if (raw == null) {
			return null;
		}
		String value = raw.strip().toUpperCase(Locale.ROOT);
		return value.length() == 2 && ISO_COUNTRIES.contains(value) ? value : null;
	}
}
