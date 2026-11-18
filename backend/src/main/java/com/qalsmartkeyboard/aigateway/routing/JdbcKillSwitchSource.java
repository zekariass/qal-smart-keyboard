package com.qalsmartkeyboard.aigateway.routing;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads the active BACKEND {@code app_configurations} row {@code ai.kill_switch} (never served to clients). */
public class JdbcKillSwitchSource implements Supplier<KillSwitch.Switches> {

	public static final String CONFIG_KEY = "ai.kill_switch";

	private static final String SQL = """
			SELECT 'P' AS kind, e AS code
			  FROM app_configurations c, jsonb_array_elements_text(
			       CASE WHEN jsonb_typeof(c.config_value -> 'providers') = 'array' THEN c.config_value -> 'providers'
			            ELSE '[]'::jsonb END) AS e
			 WHERE c.config_key = :key AND c.platform_scope = 'BACKEND' AND c.is_active
			UNION ALL
			SELECT 'F', e
			  FROM app_configurations c, jsonb_array_elements_text(
			       CASE WHEN jsonb_typeof(c.config_value -> 'features') = 'array' THEN c.config_value -> 'features'
			            ELSE '[]'::jsonb END) AS e
			 WHERE c.config_key = :key AND c.platform_scope = 'BACKEND' AND c.is_active
			""";

	private final JdbcClient jdbc;

	public JdbcKillSwitchSource(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public KillSwitch.Switches get() {
		Set<String> providers = new HashSet<>();
		Set<String> features = new HashSet<>();
		jdbc.sql(SQL).param("key", CONFIG_KEY).query(rs -> {
			(rs.getString("kind").equals("P") ? providers : features).add(rs.getString("code"));
		});
		return new KillSwitch.Switches(providers, features);
	}
}
