package com.qalsmartkeyboard.tools;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/tools/fx-rates} (contract operation {@code getFxRates}). {@code security: [{}, bearerAuth]}:
 * anonymous is enough (the converter works before sign-in too), a presented token must be valid. Read-only,
 * unmetered, identical for every caller, hence publicly cacheable for an hour.
 */
@RestController
class FxRatesController {

	private final FxRatesService service;

	FxRatesController(FxRatesService service) {
		this.service = service;
	}

	@GetMapping("/api/v1/tools/fx-rates")
	ResponseEntity<FxRatesDto> getFxRates() {
		return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
				.body(service.current());
	}
}
