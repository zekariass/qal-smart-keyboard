package com.qalsmartkeyboard.testsupport.contract;

import java.util.Locale;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.MockMvcResponse;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.report.ValidationReport;

/**
 * Makes every MockMvc response in a test class validate against the OpenAPI contract: an undeclared path or
 * status, or a header, content type or body-schema violation, fails the test at the perform(...) call.
 * Only the RESPONSE is validated (requests may be deliberately invalid in negative tests). Activate with
 * {@link ContractValidated}. A test may supply its own {@link OpenApiInteractionValidator} bean (for example with
 * test-only operations) to replace the default validator over the real contract.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContractValidationConfiguration {

	@Bean
	MockMvcBuilderCustomizer openApiContractValidation(ObjectProvider<OpenApiInteractionValidator> custom) {
		OpenApiInteractionValidator validator = custom.getIfAvailable(OpenApiContract::validator);
		return builder -> builder.alwaysDo(result -> {
			var request = result.getRequest();
			ValidationReport report = validator.validateResponse(request.getRequestURI(),
					Request.Method.valueOf(request.getMethod().toUpperCase(Locale.ROOT)),
					MockMvcResponse.of(result.getResponse()));
			if (report.hasErrors()) {
				throw new AssertionError("Response violates contracts/openapi.yaml: " + request.getMethod() + " "
						+ request.getRequestURI() + " -> " + result.getResponse().getStatus() + "\n"
						+ report.getMessages());
			}
		});
	}
}
