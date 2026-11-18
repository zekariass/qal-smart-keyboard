package com.qalsmartkeyboard.entitlements;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the seed straight out of {@code V1__baseline.sql} and {@code V18__premium_themes.sql} (plans, feature catalogue and the PROVISIONAL_V2 allowances),
 * so the tests compare the resolver with the migration itself and cannot drift from it. The migration states the
 * rule for the rest: rows listed in {@code enabled_seed} are enabled, every other (plan, gated feature) pair is
 * inserted disabled (included 0 for metered features, NULL for NONE, reset NONE).
 */
final class SeedSql {

	record Plan(String code, String displayName, int rank, boolean isDefault) {
	}

	record Feature(String code, MeteringUnit unit, boolean gated, BigDecimal creditCost) {
	}

	record Allowance(String planCode, String featureCode, Long includedUnits, ResetPeriod period) {
	}

	final List<Plan> plans = new ArrayList<>();
	final List<Feature> catalog = new ArrayList<>();
	/** Enabled rows keyed by plan code + "/" + feature code. */
	final Map<String, Allowance> enabled = new LinkedHashMap<>();

	private static SeedSql cached;

	static synchronized SeedSql load() {
		if (cached == null) {
			cached = new SeedSql(readMigration("V1__baseline.sql"));
			cached.parseFeatures(readMigration("V18__premium_themes.sql"));
		}
		return cached;
	}

	private SeedSql(String sql) {
		Matcher plan = Pattern.compile(
				"\\('([A-Z_]+)',\\s*'((?:[^']|'')*)',\\s*'(?:[^']|'')*',\\s*'(?:DRAFT|ACTIVE|RETIRED)',\\s*(-?\\d+),\\s*'(?:CONSUMER|BUSINESS)',\\s*(?:TRUE|FALSE),\\s*(TRUE|FALSE),")
				.matcher(section(sql, "INSERT INTO subscription_plans", "ON CONFLICT"));
		while (plan.find()) {
			plans.add(new Plan(plan.group(1), plan.group(2).replace("''", "'"), Integer.parseInt(plan.group(3)),
					Boolean.parseBoolean(plan.group(4))));
		}

		parseFeatures(sql);
	}

	/** Adds the feature rows and enabled allowances of one migration (V1, then V18 adds PREMIUM_THEMES). */
	private void parseFeatures(String sql) {
		Matcher feature = Pattern.compile(
				"\\('([A-Z_]+)',\\s*'(?:[^']|'')*',\\s*'(?:TYPING|TEXT_AI|VOICE|REALTIME|PLATFORM)',\\s*'([A-Z]+)',\\s*(TRUE|FALSE),\\s*([0-9.]+),")
				.matcher(section(sql, "INSERT INTO feature_catalog", "ON CONFLICT"));
		while (feature.find()) {
			catalog.add(new Feature(feature.group(1), MeteringUnit.valueOf(feature.group(2)),
					Boolean.parseBoolean(feature.group(3)), new BigDecimal(feature.group(4))));
		}

		Matcher row = Pattern.compile("\\('([A-Z_]+)',\\s*'([A-Z_]+)',\\s*(NULL|\\d+)::BIGINT,\\s*'([A-Z]+)'\\)")
				.matcher(section(sql, "WITH enabled_seed", "INSERT INTO plan_entitlements"));
		while (row.find()) {
			Long included = "NULL".equals(row.group(3)) ? null : Long.valueOf(row.group(3));
			enabled.put(row.group(1) + "/" + row.group(2), new Allowance(row.group(1), row.group(2), included,
					ResetPeriod.valueOf(row.group(4))));
		}
	}

	/** The seed's own claim about (plan, gated feature): the enabled row, or null for the implicit disabled row. */
	Allowance enabledRow(String planCode, String featureCode) {
		return enabled.get(planCode + "/" + featureCode);
	}

	Feature feature(String code) {
		return catalog.stream().filter(f -> f.code().equals(code)).findFirst().orElseThrow();
	}

	private static String section(String sql, String from, String to) {
		int start = sql.indexOf(from);
		if (start < 0) {
			throw new IllegalStateException("V1 seed marker not found: " + from);
		}
		int end = sql.indexOf(to, start + from.length());
		if (end < 0) {
			throw new IllegalStateException("V1 seed marker not found: " + to);
		}
		return sql.substring(start, end);
	}

	private static String readMigration(String name) {
		try (InputStream in = SeedSql.class.getResourceAsStream("/db/migration/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
