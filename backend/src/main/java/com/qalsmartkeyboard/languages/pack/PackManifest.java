package com.qalsmartkeyboard.languages.pack;

import java.math.BigInteger;

import tools.jackson.databind.JsonNode;

/**
 * The validated members of a signed manifest (pack spec section 5.2). Optional members are {@code null} when absent or
 * {@code null}. {@code deviceRequirements} and {@code profile} are type-checked JSON objects, interpreted by the host.
 */
public record PackManifest(String resourceId, String languageCode, String resourceType, SemVer version, String platform,
		String releaseChannel, String sha256, BigInteger sizeBytes, String baseResourceId, SemVer minAppVersion,
		String licenseCode, String signatureKeyId, String artifactUrl, JsonNode deviceRequirements, JsonNode profile) {

	public boolean isDelta() {
		return baseResourceId != null;
	}
}
