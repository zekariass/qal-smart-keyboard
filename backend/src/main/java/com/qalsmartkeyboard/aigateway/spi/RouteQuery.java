package com.qalsmartkeyboard.aigateway.spi;

import java.util.Objects;

/**
 * What the pipeline needs from the registry. {@code sourceLanguage} null = auto-detect (translate) or unknown
 * (transform); {@code destinationLanguage} is null for transform and detect features. {@code inputCharacters} 0 skips
 * the per-capability size check. {@code minimumTier} defaults to BETA: only BETA and PRODUCTION reach users (SD 11.2).
 */
public record RouteQuery(AiFeature feature, String sourceLanguage, String destinationLanguage,
		QualityTier minimumTier, DataPolicyRequirement dataPolicy, int inputCharacters) {

	public RouteQuery {
		Objects.requireNonNull(feature, "feature");
		minimumTier = minimumTier == null ? QualityTier.BETA : minimumTier;
		dataPolicy = dataPolicy == null ? DataPolicyRequirement.STANDARD : dataPolicy;
	}

	public static RouteQuery of(AiFeature feature, String sourceLanguage, String destinationLanguage) {
		return new RouteQuery(feature, sourceLanguage, destinationLanguage, null, null, 0);
	}

	public RouteQuery withDataPolicy(DataPolicyRequirement requirement) {
		return new RouteQuery(feature, sourceLanguage, destinationLanguage, minimumTier, requirement, inputCharacters);
	}

	public RouteQuery withMinimumTier(QualityTier tier) {
		return new RouteQuery(feature, sourceLanguage, destinationLanguage, tier, dataPolicy, inputCharacters);
	}

	public RouteQuery withInputCharacters(int characters) {
		return new RouteQuery(feature, sourceLanguage, destinationLanguage, minimumTier, dataPolicy, characters);
	}
}
