package com.qalsmartkeyboard.privacy;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.privacy.DataExportService.Export;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

/**
 * {@code requestDataExport}, {@code getDataExport} and {@code downloadDataExport} (SD 19.4, ADR 0022). Any valid access
 * token may use them, guests included. Every response is {@code no-store}; the archive is sent as an attachment.
 */
@RestController
class DataExportController {

	private final DataExportService exports;
	private final DataExportProperties properties;
	private final Clock clock;

	DataExportController(DataExportService exports, DataExportProperties properties, ObjectProvider<Clock> clock) {
		this.exports = exports;
		this.properties = properties;
		this.clock = clock.getIfAvailable(Clock::systemUTC);
	}

	/** @param format only {@code JSON} (the contract's enum); absent means {@code JSON} */
	record DataExportCreateDto(@Pattern(regexp = "JSON") String format) {
	}

	/** Contract {@code DataExport}. {@code downloadUrl} is present while the export is {@code READY}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record DataExportDto(UUID id, String status, Instant requestedAt, Instant readyAt, String downloadUrl,
			Instant downloadExpiresAt) {
	}

	@PostMapping("/api/v1/me/data-exports")
	ResponseEntity<DataExportDto> requestDataExport(@AuthenticationPrincipal AuthenticatedUser caller,
			@Valid @RequestBody(required = false) DataExportCreateDto body) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
				.body(dto(exports.request(caller.userId())));
	}

	@GetMapping("/api/v1/me/data-exports/{exportId}")
	ResponseEntity<DataExportDto> getDataExport(@AuthenticationPrincipal AuthenticatedUser caller,
			@PathVariable UUID exportId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dto(exports.get(caller.userId(), exportId)));
	}

	/**
	 * The archive of a {@code READY} export: the decrypted JSON document, only for its owner's access token, only until
	 * the link expires.
	 */
	@GetMapping(path = "/api/v1/me/data-exports/{exportId}/archive", produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<byte[]> downloadDataExport(@AuthenticationPrincipal AuthenticatedUser caller,
			@PathVariable UUID exportId) {
		byte[] archive = exports.download(caller.userId(), exportId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename("qal-data-export-" + exportId + ".json").build()
								.toString())
				.header("X-Content-Type-Options", "nosniff").contentType(MediaType.APPLICATION_JSON).body(archive);
	}

	private DataExportDto dto(Export export) {
		String status = export.apiStatus(clock.instant());
		boolean ready = "READY".equals(status);
		return new DataExportDto(export.id(), status, export.requestedAt(), ready ? export.completedAt() : null,
				ready ? downloadUrl(export.id()) : null, ready ? export.downloadExpiresAt() : null);
	}

	private String downloadUrl(UUID id) {
		String base = properties.publicBaseUrl() != null && !properties.publicBaseUrl().isBlank()
				? properties.publicBaseUrl().strip().replaceAll("/+$", "")
				: ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
		return base + "/api/v1/me/data-exports/" + id + "/archive";
	}
}
