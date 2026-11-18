package com.qalsmartkeyboard.aigateway;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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

	private final JdbcClient jdbc;

	JdbcProviderDescriptorSource(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<ProviderDescriptor> loadAll() {
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
				b.capabilities.add(new ProviderCapability(aiFeature, rs.getString("source_language"),
						rs.getString("destination_language"), QualityTier.valueOf(rs.getString("quality_tier")),
						rs.getBoolean("supports_auto_detection"), noMax ? null : max, rs.getString("model_code")));
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
