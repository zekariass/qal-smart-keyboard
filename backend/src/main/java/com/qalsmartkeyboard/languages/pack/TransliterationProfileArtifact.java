package com.qalsmartkeyboard.languages.pack;

import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the profile id of a {@code TRANSLITERATION_RULES} artifact: a {@code qal-transliteration-profile} JSON document
 * (C1.4, {@code language-data/profiles/}) whose {@code profileId} names the profile. ADR 0007 D21 makes the backend
 * {@code transliteration_profiles.profile_code} the canonical code, so the publisher requires the two to be equal.
 */
public final class TransliterationProfileArtifact {

	public static final String FORMAT = "qal-transliteration-profile";

	private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
			.streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build()).build()).build();

	private TransliterationProfileArtifact() {
	}

	/** The {@code profileId}, or empty if the artifact is not a profile document (or has no string id). */
	public static Optional<String> profileId(byte[] artifact) {
		try {
			JsonNode doc = MAPPER.readTree(artifact);
			if (doc == null || !doc.isObject() || !FORMAT.equals(doc.path("format").asString(null))) {
				return Optional.empty();
			}
			JsonNode id = doc.get("profileId");
			return id != null && id.isString() && !id.asString().isBlank() ? Optional.of(id.asString())
					: Optional.empty();
		}
		catch (JacksonException e) {
			return Optional.empty();
		}
	}
}
