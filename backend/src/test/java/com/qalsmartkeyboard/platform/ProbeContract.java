package com.qalsmartkeyboard.platform;

import java.util.List;
import java.util.Map;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.qalsmartkeyboard.testsupport.contract.OpenApiContract;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

/**
 * The real contract plus test-only operations for the probe controller. The probe operations reuse the
 * contract's own shared error responses ({@code components.responses.*}: the {@code Problem} schema with the
 * {@code X-Correlation-Id} header) for every error status, so each Problem produced by the platform is validated
 * against the contract's Problem schema. The contract has no 405/406/415 responses, so those statuses reuse the same
 * Problem response object here. Nothing is written back to contracts/openapi.yaml.
 */
final class ProbeContract {

	static final List<String> PATHS = List.of("/probe/validated", "/probe/limit", "/probe/api-exception", "/probe/quota",
			"/probe/boom", "/probe/ping", "/probe/violating", "/probe/no-such-route");

	private ProbeContract() {
	}

	static OpenApiInteractionValidator validator() {
		return OpenApiContract.validatorWithOverlay(api -> {
			Map<String, ApiResponse> shared = api.getComponents().getResponses();
			for (String path : PATHS) {
				PathItem item = new PathItem();
				item.setGet(operation(shared));
				item.setPost(operation(shared));
				api.getPaths().addPathItem(path, item);
			}
		});
	}

	private static Operation operation(Map<String, ApiResponse> shared) {
		ApiResponses responses = new ApiResponses();
		responses.addApiResponse("200", new ApiResponse().description("ok").content(
				new Content().addMediaType("application/json", new MediaType().schema(new ObjectSchema()))));
		responses.addApiResponse("400", shared.get("BadRequest"));
		responses.addApiResponse("401", shared.get("Unauthorized"));
		responses.addApiResponse("403", shared.get("Forbidden"));
		responses.addApiResponse("404", shared.get("NotFound"));
		responses.addApiResponse("405", shared.get("NotFound"));
		responses.addApiResponse("406", shared.get("NotFound"));
		responses.addApiResponse("409", shared.get("Conflict"));
		responses.addApiResponse("415", shared.get("NotFound"));
		responses.addApiResponse("422", shared.get("Unprocessable"));
		responses.addApiResponse("426", shared.get("NotFound"));
		responses.addApiResponse("429", shared.get("TooManyRequests"));
		responses.addApiResponse("500", shared.get("NotFound"));
		responses.addApiResponse("503", shared.get("ServiceUnavailable"));
		return new Operation().responses(responses);
	}
}
