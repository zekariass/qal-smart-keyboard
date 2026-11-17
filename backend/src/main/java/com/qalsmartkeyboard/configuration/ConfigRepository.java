package com.qalsmartkeyboard.configuration;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only access to {@code feature_flags} and {@code app_configurations}. JSON columns are read as text. */
@Repository
class ConfigRepository {

	record FlagRow(String flagCode, boolean enabled, int rolloutPercentage, String platformScope, String minAppVersion,
			String targetingJson) {
	}

	record ConfigRow(String key, String platformScope, int version, String valueJson) {
	}

	private final JdbcTemplate jdbc;

	ConfigRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	List<FlagRow> flags() {
		return jdbc.query("""
				SELECT flag_code, is_enabled, rollout_percentage, platform_scope, min_app_version, targeting::text
				FROM feature_flags ORDER BY flag_code""",
				(rs, i) -> new FlagRow(rs.getString(1), rs.getBoolean(2), rs.getInt(3), rs.getString(4),
						rs.getString(5), rs.getString(6)));
	}

	/** Active rows that clients may see (never the BACKEND scope). */
	List<ConfigRow> activeClientConfigs() {
		return jdbc.query("""
				SELECT config_key, platform_scope, config_version, config_value::text
				FROM app_configurations
				WHERE is_active AND platform_scope IN ('ALL', 'ANDROID', 'IOS', 'WEB')
				ORDER BY config_key, platform_scope""",
				(rs, i) -> new ConfigRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)));
	}
}
