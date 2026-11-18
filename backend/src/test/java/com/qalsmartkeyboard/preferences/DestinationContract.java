package com.qalsmartkeyboard.preferences;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.qalsmartkeyboard.testsupport.contract.OpenApiContract;

import io.swagger.v3.oas.models.media.Schema;

/**
 * The contract's {@code DestinationProfile} is {@code allOf [DestinationProfileInput, {id, createdAt, updatedAt}]} and
 * neither member declares {@code additionalProperties}; the validator then rejects the other member's properties, so
 * no correct response can pass. The overlay (in memory, the contract file is untouched) sets
 * {@code additionalProperties: true} on the {@code allOf} members of the destination-profile responses, the way the
 * contract does for {@code Problem}. Reported as a contract gap.
 */
@TestConfiguration(proxyBeanMethods = false)
class DestinationContract {

	@Bean
	OpenApiInteractionValidator destinationContractValidator() {
		return OpenApiContract.validatorWithOverlay(api -> api.getPaths().forEach((path, item) -> {
			if (path.startsWith("/api/v1/me/destination-profiles")) {
				item.readOperations().forEach(op -> op.getResponses().values().forEach(response -> {
					if (response.getContent() != null) {
						response.getContent().values().forEach(media -> open(media.getSchema()));
					}
				}));
			}
		}));
	}

	@SuppressWarnings("rawtypes")
	private static void open(Schema schema) {
		if (schema == null) {
			return;
		}
		if (schema.getAllOf() != null) {
			for (Object member : schema.getAllOf()) {
				Schema s = (Schema) member;
				s.setAdditionalProperties(Boolean.TRUE);
				open(s);
			}
		}
		if (schema.getProperties() != null) {
			schema.getProperties().values().forEach(p -> open((Schema) p));
		}
		open(schema.getItems());
	}
}
