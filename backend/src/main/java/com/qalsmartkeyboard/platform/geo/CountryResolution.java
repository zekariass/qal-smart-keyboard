package com.qalsmartkeyboard.platform.geo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The outcome of billing-country detection (ADR 0045). Holds country codes and enum values only, never an IP address,
 * so it can be logged and stored on an order for audit ({@link #summary()}).
 *
 * @param country        the resolved ISO 3166-1 alpha-2 country, or {@code null} when nothing usable was found
 * @param source         which signal decided {@code country}
 * @param confidence     how much the signals support it
 * @param ipCountry      the edge's country when the edge is trusted and it named a real country, else {@code null}
 * @param ipTrusted      whether the request provably came through the configured edge (or the dev override applied)
 * @param networkCountry the valid {@code X-Qal-Network-Country} hint, normalised, or {@code null}
 * @param simCountry     the valid {@code X-Qal-Sim-Country} hint, normalised, or {@code null}
 * @param flags          mismatch and anomaly flags
 */
public record CountryResolution(String country, Source source, Confidence confidence, String ipCountry,
		boolean ipTrusted, String networkCountry, String simCountry, Set<Flag> flags) {

	/** Which signal decided the country. */
	public enum Source {
		/** {@code CF-IPCountry} from a trusted edge. */
		IP_EDGE,
		/** {@code DEV_MOCK_COUNTRY} (development and test profiles only). */
		DEV_MOCK,
		/** Network and SIM hints agree and no trusted IP country exists (weak: a modified client can send anything). */
		CLIENT_HINTS,
		/** Nothing usable. */
		NONE
	}

	/** Ordered weakest first so {@link #atLeast} can compare. */
	public enum Confidence {
		NONE, LOW, MEDIUM, HIGH;

		public boolean atLeast(Confidence other) {
			return compareTo(other) >= 0;
		}
	}

	/** Anomalies worth recording. A single mismatch is travel or roaming as often as anything else, never "VPN". */
	public enum Flag {
		/** A {@code CF-IPCountry} header arrived but the request did not come through the trusted edge (ignored). */
		IP_UNTRUSTED,
		/** The trusted edge did not know the country ({@code XX}, absent or malformed). */
		IP_UNKNOWN,
		/** The trusted edge reported Tor ({@code T1}). */
		ANONYMIZER,
		IP_SIM_MISMATCH,
		IP_NETWORK_MISMATCH,
		NETWORK_SIM_MISMATCH,
		/** The IP country differs from the network and SIM countries, which agree with each other. */
		POSSIBLE_VPN_OR_PROXY,
		/** A client hint was present but not an ISO 3166-1 alpha-2 country (ignored). */
		INVALID_HINT
	}

	public CountryResolution {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(confidence, "confidence");
		flags = flags == null || flags.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(flags));
	}

	public Optional<String> countryCode() {
		return Optional.ofNullable(country);
	}

	public boolean has(Flag flag) {
		return flags.contains(flag);
	}

	/** Country codes, enums and booleans for audit records and logs: never an IP address or a raw header value. */
	public Map<String, Object> summary() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("country", country);
		out.put("source", source.name());
		out.put("confidence", confidence.name());
		out.put("ipCountry", ipCountry);
		out.put("ipTrusted", ipTrusted);
		out.put("networkCountry", networkCountry);
		out.put("simCountry", simCountry);
		List<String> names = new ArrayList<>();
		flags.forEach(f -> names.add(f.name()));
		out.put("flags", names);
		return out;
	}
}
