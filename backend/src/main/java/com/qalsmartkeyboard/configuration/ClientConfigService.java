package com.qalsmartkeyboard.configuration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qalsmartkeyboard.configuration.ConfigRepository.ConfigRow;
import com.qalsmartkeyboard.configuration.ConfigRepository.FlagRow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves the client configuration from {@code feature_flags} and {@code app_configurations}.
 *
 * <p>The tables are read as one in-memory snapshot that is reused for {@code qal.config.cache-ttl}, so neither
 * {@code GET /config} nor the per-request version check touches the database on every call. A change in the tables
 * becomes visible after the TTL (explicit invalidation, for example from the admin module, comes later). If a
 * reload fails the previous snapshot keeps being served and the reload is retried after a few seconds.
 *
 * <h2>Resolution</h2>
 * <ul>
 * <li><b>Values:</b> active {@code app_configurations} rows with scope {@code ALL}, overridden per key by the row of
 * the caller's platform scope. {@code BACKEND} rows are never exposed. {@code minSupportedAppVersion} and
 * {@code recommendedAppVersion} are reserved keys (JSON string values) that feed the top-level response fields.
 * With no {@code minSupportedAppVersion} row the minimum is {@code 0.0.0} (nothing is rejected).
 * {@code configVersion} is the highest {@code config_version} among the rows applied to the caller (0 if none).</li>
 * <li><b>Flags:</b> every flag appears in the response; it is {@code true} only if: {@code is_enabled}; its
 * {@code platform_scope} is {@code ALL} or the caller's platform; {@code min_app_version} (if set) is met by the
 * caller's version (an unknown version does not meet it); {@code targeting} is empty (the keys markets/plans/
 * distributionChannels need a user context that anonymous callers do not have, so a targeted flag is {@code false}
 * until the authenticated context is wired in with B2.4); and the rollout admits the caller: 100 admits everyone,
 * 0 nobody, otherwise the caller's bucket {@code SHA-256(flagCode + ":" + subjectKey)[0..4) mod 100} must be below
 * {@code rollout_percentage}. The stable {@code subjectKey} (user id, else device id) is supplied by the caller of
 * {@link #resolve}; until authentication exists it is null and partial rollouts evaluate to {@code false}.
 * {@code user_feature_overrides} are applied together with the authenticated context (B2.4).</li>
 * </ul>
 */
class ClientConfigService {

	static final String MIN_VERSION_KEY = "minSupportedAppVersion";
	static final String RECOMMENDED_VERSION_KEY = "recommendedAppVersion";
	static final String NO_MINIMUM = "0.0.0";
	private static final long RETRY_AFTER_FAILURE_MS = 5_000;
	private static final Logger log = LoggerFactory.getLogger(ClientConfigService.class);

	private record Snapshot(List<FlagRow> flags, List<ConfigRow> configs, Map<String, JsonNode> parsedValues,
			Instant expiresAt) {
	}

	private final ConfigRepository repository;
	private final ConfigProperties properties;
	private final Clock clock;
	private final JsonMapper json = JsonMapper.builder().build();
	private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

	ClientConfigService(ConfigRepository repository, ConfigProperties properties, Clock clock) {
		this.repository = repository;
		this.properties = properties;
		this.clock = clock;
	}

	/** The lowest app version allowed for the platform ({@code null} = unknown platform: only the ALL scope). */
	AppVersion minSupported(ClientPlatform platform) {
		Snapshot s = current();
		return versionValue(s, platform, MIN_VERSION_KEY).orElse(new AppVersion(0, 0, 0, null));
	}

	ClientConfigDto resolve(ClientPlatform platform, AppVersion version, String subjectKey) {
		Snapshot s = current();
		Map<String, ConfigRow> applied = appliedRows(s, platform);

		Map<String, Object> values = new LinkedHashMap<>();
		int configVersion = 0;
		for (ConfigRow row : applied.values()) {
			configVersion = Math.max(configVersion, row.version());
			if (!MIN_VERSION_KEY.equals(row.key()) && !RECOMMENDED_VERSION_KEY.equals(row.key())) {
				values.put(row.key(), json.convertValue(s.parsedValues().get(id(row)), Object.class));
			}
		}
		Map<String, Boolean> flags = new LinkedHashMap<>();
		for (FlagRow f : s.flags()) {
			flags.put(f.flagCode(), evaluate(f, platform, version, subjectKey));
		}
		String min = versionValue(s, platform, MIN_VERSION_KEY).map(AppVersion::toString).orElse(NO_MINIMUM);
		String recommended = versionValue(s, platform, RECOMMENDED_VERSION_KEY).map(AppVersion::toString).orElse(null);
		return new ClientConfigDto(configVersion, min, recommended, flags, values, clock.instant());
	}

	boolean evaluate(FlagRow f, ClientPlatform platform, AppVersion version, String subjectKey) {
		if (!f.enabled()) {
			return false;
		}
		if (!"ALL".equals(f.platformScope()) && (platform == null || !platform.name().equals(f.platformScope()))) {
			return false;
		}
		if (f.minAppVersion() != null) {
			Optional<AppVersion> min = AppVersion.parse(f.minAppVersion());
			if (min.isEmpty()) {
				log.warn("Flag {} has an unparsable min_app_version; treated as not met", f.flagCode());
				return false;
			}
			if (version == null || version.compareTo(min.get()) < 0) {
				return false;
			}
		}
		if (hasTargeting(f)) {
			return false;
		}
		return inRollout(f.flagCode(), f.rolloutPercentage(), subjectKey);
	}

	/** Deterministic percentage rollout; see the class comment for the key. */
	static boolean inRollout(String flagCode, int percentage, String subjectKey) {
		if (percentage >= 100) {
			return true;
		}
		if (percentage <= 0 || subjectKey == null || subjectKey.isEmpty()) {
			return false;
		}
		return bucket(flagCode, subjectKey) < percentage;
	}

	static int bucket(String flagCode, String subjectKey) {
		try {
			byte[] h = MessageDigest.getInstance("SHA-256")
					.digest((flagCode + ":" + subjectKey).getBytes(StandardCharsets.UTF_8));
			long v = ((h[0] & 0xFFL) << 24) | ((h[1] & 0xFFL) << 16) | ((h[2] & 0xFFL) << 8) | (h[3] & 0xFFL);
			return (int) (v % 100);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private boolean hasTargeting(FlagRow f) {
		try {
			JsonNode t = json.readTree(f.targetingJson());
			if (t == null || t.isNull()) {
				return false;
			}
			if (!t.isObject()) {
				return true;
			}
			for (JsonNode v : t.values()) {
				if (!(v.isNull() || (v.isContainer() && v.isEmpty()))) {
					return true;
				}
			}
			return false;
		}
		catch (RuntimeException e) {
			return true; // cannot interpret: conservative
		}
	}

	private Optional<AppVersion> versionValue(Snapshot s, ClientPlatform platform, String key) {
		ConfigRow row = appliedRows(s, platform).get(key);
		if (row == null) {
			return Optional.empty();
		}
		JsonNode v = s.parsedValues().get(id(row));
		Optional<AppVersion> parsed = v != null && v.isString() ? AppVersion.parse(v.asString()) : Optional.empty();
		if (parsed.isEmpty()) {
			log.warn("app_configurations {} ({}) is not a valid version string; ignored", key, row.platformScope());
		}
		return parsed;
	}

	/** Platform row wins over the ALL row, per key. */
	private Map<String, ConfigRow> appliedRows(Snapshot s, ClientPlatform platform) {
		Map<String, ConfigRow> out = new LinkedHashMap<>();
		for (ConfigRow r : s.configs()) {
			if ("ALL".equals(r.platformScope())) {
				out.putIfAbsent(r.key(), r);
			}
		}
		if (platform != null) {
			for (ConfigRow r : s.configs()) {
				if (platform.name().equals(r.platformScope())) {
					out.put(r.key(), r);
				}
			}
		}
		return out;
	}

	private static String id(ConfigRow r) {
		return r.key() + "|" + r.platformScope();
	}

	private Snapshot current() {
		Snapshot s = snapshot.get();
		Instant now = clock.instant();
		if (s != null && now.isBefore(s.expiresAt())) {
			return s;
		}
		synchronized (this) {
			s = snapshot.get();
			if (s != null && now.isBefore(s.expiresAt())) {
				return s;
			}
			try {
				List<ConfigRow> configs = repository.activeClientConfigs();
				Map<String, JsonNode> parsed = new HashMap<>();
				for (ConfigRow r : configs) {
					parsed.put(id(r), json.readTree(r.valueJson()));
				}
				Snapshot fresh = new Snapshot(repository.flags(), configs, parsed, now.plus(properties.cacheTtl()));
				snapshot.set(fresh);
				return fresh;
			}
			catch (RuntimeException e) {
				if (s == null) {
					throw e;
				}
				log.warn("Configuration reload failed; serving the previous snapshot", e);
				Snapshot stale = new Snapshot(s.flags(), s.configs(), s.parsedValues(),
						now.plusMillis(RETRY_AFTER_FAILURE_MS));
				snapshot.set(stale);
				return stale;
			}
		}
	}
}
