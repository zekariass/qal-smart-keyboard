package com.qalsmartkeyboard.aigateway;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.DataPolicy;
import com.qalsmartkeyboard.aigateway.spi.ProviderCapability;
import com.qalsmartkeyboard.aigateway.spi.ProviderDescriptor;
import com.qalsmartkeyboard.aigateway.spi.ProviderStatus;
import com.qalsmartkeyboard.aigateway.spi.ProviderType;
import com.qalsmartkeyboard.aigateway.spi.QualityTier;

/**
 * Reads ai_providers, ai_models and ai_language_capabilities (V1). Only SERVER models in status ACTIVE and capabilities
 * with {@code is_enabled} contribute; a feature code the text SPI does not serve (voice, platform) is skipped. Provider
 * secrets are not read: {@code secret_reference} and {@code configuration} stay in the database for operators.
 *
 * <p>ADR 0040: a TRANSLATE row with a null source or destination is limited, on that side, to the model's enabled
 * {@code ai_model_translation_languages} of an active catalogue language in that role (an empty set when the model has
 * none, so such a row never means "any language at all").
 */
class JdbcProviderDescriptorSource implements ProviderDescriptorSource {

	private static final String SQL = """
			SELECT p.provider_code, p.display_name, p.provider_type, p.provider_status, p.zero_data_retention,
			       p.trains_on_customer_data, p.processing_regions, (p.dpa_signed_at IS NOT NULL) AS dpa_signed,
			       m.model_code, c.feature_code, sl.language_code AS source_language, dl.language_code AS destination_language,
			       c.supports_auto_detection, c.quality_tier, c.max_input_characters
			  FROM ai_providers p
			  LEFT JOIN ai_models m ON m.provider_id = p.id AND m.model_status = 'ACTIVE' AND m.deployment = 'SERVER'
			  LEFT JOIN ai_language_capabilities c ON c.model_id = m.id AND c.is_enabled
			  LEFT JOIN supported_languages sl ON sl.id = c.source_language_id
			  LEFT JOIN supported_languages dl ON dl.id = c.destination_language_id
			 ORDER BY p.provider_code, m.model_code, c.feature_code
			""";

	private static final String TRANSLATION_LANGUAGES = """
			SELECT p.provider_code, m.model_code, l.language_code, t.as_source, t.as_destination
			  FROM ai_model_translation_languages t
			  JOIN ai_models m ON m.id = t.model_id
			  JOIN ai_providers p ON p.id = m.provider_id
			  JOIN supported_languages l ON l.id = t.language_id
			 WHERE t.is_enabled AND l.is_active
			""";

	/** The translation languages of one model, per role. */
	private record LanguageSets(Set<String> sources, Set<String> destinations) {
	}

	private final JdbcClient jdbc;

	JdbcProviderDescriptorSource(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<ProviderDescriptor> loadAll() {
		Map<String, LanguageSets> sets = new HashMap<>();
		jdbc.sql(TRANSLATION_LANGUAGES).query((ResultSet rs) -> {
			LanguageSets s = sets.computeIfAbsent(rs.getString("provider_code") + "|" + rs.getString("model_code"),
					k -> new LanguageSets(new HashSet<>(), new HashSet<>()));
			if (rs.getBoolean("as_source")) {
				s.sources().add(rs.getString("language_code"));
			}
			if (rs.getBoolean("as_destination")) {
				s.destinations().add(rs.getString("language_code"));
			}
		});
		LanguageSets none = new LanguageSets(Set.of(), Set.of());
		Map<String, Builder> byCode = new LinkedHashMap<>();
		jdbc.sql(SQL).query((ResultSet rs) -> {
			String code = rs.getString("provider_code");
			Builder b = byCode.get(code);
			if (b == null) {
				b = new Builder(code, rs);
				byCode.put(code, b);
			}
			String feature = rs.getString("feature_code");
			AiFeature aiFeature = feature == null ? null : AiFeature.fromCode(feature);
			if (aiFeature != null) {
				int max = rs.getInt("max_input_characters");
				boolean noMax = rs.wasNull();
				String model = rs.getString("model_code");
				LanguageSets s = aiFeature == AiFeature.TRANSLATE ? sets.getOrDefault(code + "|" + model, none) : null;
				b.capabilities.add(new ProviderCapability(aiFeature, rs.getString("source_language"),
						rs.getString("destination_language"), QualityTier.valueOf(rs.getString("quality_tier")),
						rs.getBoolean("supports_auto_detection"), noMax ? null : max, model,
						s == null ? null : s.sources(), s == null ? null : s.destinations()));
			}
		});
		return byCode.values().stream().map(Builder::build).toList();
	}

	private static final class Builder {
		final String code;
		final String displayName;
		final ProviderType type;
		final ProviderStatus status;
		final DataPolicy dataPolicy;
		final List<ProviderCapability> capabilities = new ArrayList<>();

		Builder(String code, ResultSet rs) throws SQLException {
			this.code = code;
			this.displayName = rs.getString("display_name");
			this.type = ProviderType.valueOf(rs.getString("provider_type"));
			this.status = ProviderStatus.valueOf(rs.getString("provider_status"));
			Set<String> regions = new HashSet<>();
			java.sql.Array array = rs.getArray("processing_regions");
			if (array != null) {
				for (Object o : (Object[]) array.getArray()) {
					regions.add(String.valueOf(o));
				}
			}
			this.dataPolicy = new DataPolicy(rs.getBoolean("zero_data_retention"),
					rs.getBoolean("trains_on_customer_data"), regions, rs.getBoolean("dpa_signed"));
		}

		ProviderDescriptor build() {
			return new ProviderDescriptor(code, displayName, type, status, dataPolicy, capabilities);
		}
	}
}
