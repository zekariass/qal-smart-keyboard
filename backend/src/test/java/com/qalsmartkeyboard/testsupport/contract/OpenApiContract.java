package com.qalsmartkeyboard.testsupport.contract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import com.atlassian.oai.validator.OpenApiInteractionValidator;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

/**
 * Loads {@code contracts/openapi.yaml} (the synced copy of the docs contract) for response validation.
 * Override the location with {@code -Dqal.contract.path=...}; the default is relative to the {@code backend} directory.
 */
public final class OpenApiContract {

	private static final Path DEFAULT_PATH = Path.of("..", "contracts", "openapi.yaml");

	private OpenApiContract() {
	}

	public static Path path() {
		Path p = Path.of(System.getProperty("qal.contract.path", DEFAULT_PATH.toString())).toAbsolutePath().normalize();
		if (!Files.isRegularFile(p)) {
			throw new IllegalStateException("OpenAPI contract not found at " + p + " (run Maven from the backend directory)");
		}
		return p;
	}

	/** Parses the contract; fails loudly on parse errors so a broken contract can never validate silently. */
	public static OpenAPI load() {
		ParseOptions options = new ParseOptions();
		options.setResolve(true);
		options.setResolveFully(true);
		var result = new OpenAPIV3Parser().readLocation(path().toUri().toString(), null, options);
		if (result.getOpenAPI() == null || (result.getMessages() != null && !result.getMessages().isEmpty())) {
			throw new IllegalStateException("OpenAPI contract did not parse cleanly: " + result.getMessages());
		}
		return result.getOpenAPI();
	}

	/** Validator over the real contract. */
	public static OpenApiInteractionValidator validator() {
		return OpenApiInteractionValidator.createFor(load()).build();
	}

	/** Validator over the real contract plus test-only operations added by {@code overlay} (never written to disk). */
	public static OpenApiInteractionValidator validatorWithOverlay(Consumer<OpenAPI> overlay) {
		OpenAPI api = load();
		overlay.accept(api);
		return OpenApiInteractionValidator.createFor(api).build();
	}
}
