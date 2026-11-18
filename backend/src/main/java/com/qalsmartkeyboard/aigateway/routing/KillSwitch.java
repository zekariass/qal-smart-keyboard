package com.qalsmartkeyboard.aigateway.routing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import com.qalsmartkeyboard.aigateway.spi.AiFeature;

/**
 * Operator kill switches (B5.2): remove one provider, or one AI feature, from routing without a deploy. Two sources are
 * merged: static sets from configuration ({@code qal.ai.routing.killed-providers}, {@code killed-features}, for an
 * emergency restart) and the BACKEND-scope {@code app_configurations} row {@code ai.kill_switch}
 * ({@code {"providers": ["code"], "features": ["TRANSLATE"]}}), re-read at most every cache TTL. Using that row
 * rather than {@code feature_flags} keeps the switch out of {@code GET /config}, which serves every flag to clients.
 * A failed reload keeps the last snapshot; before any successful load only the static sets apply (the switch cannot
 * be read, and refusing all AI because the database blinked would be the larger harm).
 */
public class KillSwitch {

	/** What the database says is killed. Provider codes are matched case-insensitively, feature codes upper case. */
	public record Switches(Set<String> providers, Set<String> features) {

		public static final Switches NONE = new Switches(Set.of(), Set.of());

		public Switches {
			providers = lower(providers);
			features = upper(features);
		}
	}

	private final Supplier<Switches> source;
	private final Set<String> staticProviders;
	private final Set<String> staticFeatures;
	private final Duration ttl;
	private final Clock clock;

	private volatile Switches snapshot;
	private volatile Instant loadedAt = Instant.MIN;

	public KillSwitch(Supplier<Switches> source, Set<String> staticProviders, Set<String> staticFeatures, Duration ttl,
			Clock clock) {
		this.source = source;
		this.staticProviders = lower(staticProviders);
		this.staticFeatures = upper(staticFeatures);
		this.ttl = ttl;
		this.clock = clock;
	}

	public boolean providerKilled(String providerCode) {
		String code = providerCode.toLowerCase(Locale.ROOT);
		return staticProviders.contains(code) || current().providers().contains(code);
	}

	public boolean featureKilled(AiFeature feature) {
		return staticFeatures.contains(feature.name()) || current().features().contains(feature.name());
	}

	private Switches current() {
		Instant now = clock.instant();
		Switches s = snapshot;
		if (s != null && Duration.between(loadedAt, now).compareTo(ttl) < 0) {
			return s;
		}
		try {
			s = source.get();
			snapshot = s;
		} catch (RuntimeException e) {
			if (s == null) {
				s = Switches.NONE;
			}
		}
		loadedAt = now;
		return s;
	}

	private static Set<String> lower(Set<String> in) {
		Set<String> out = new HashSet<>();
		if (in != null) {
			in.forEach(c -> out.add(c.trim().toLowerCase(Locale.ROOT)));
		}
		return Set.copyOf(out);
	}

	private static Set<String> upper(Set<String> in) {
		Set<String> out = new HashSet<>();
		if (in != null) {
			in.forEach(c -> out.add(c.trim().toUpperCase(Locale.ROOT)));
		}
		return Set.copyOf(out);
	}
}
