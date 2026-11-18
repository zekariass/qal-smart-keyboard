package com.qalsmartkeyboard.languages;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.languages.AdminDtos.AdminLanguageResourceDto;
import com.qalsmartkeyboard.languages.AdminDtos.AdminSigningKeyDto;
import com.qalsmartkeyboard.languages.AdminDtos.DatasetListDto;
import com.qalsmartkeyboard.languages.AdminDtos.DatasetRegistrationRequest;
import com.qalsmartkeyboard.languages.AdminDtos.PackSignerDto;
import com.qalsmartkeyboard.languages.AdminDtos.PublishRequest;
import com.qalsmartkeyboard.languages.AdminDtos.SigningKeyRegistration;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The admin pack publishing operations of the contract's {@code Admin} tag (B3.2, ADR 0019), under {@code /admin/v1}.
 * Bearer token required (the security chain); the admin role and permission are checked per operation by
 * {@code AdminAccess}. The upload body is the raw {@code .pack} container ({@code application/octet-stream}), read with
 * a hard size cap before anything else happens.
 */
@RestController
class AdminLanguagesController {

	private final PackPublishingService publishing;
	private final SigningKeyAdminService keys;

	AdminLanguagesController(PackPublishingService publishing, SigningKeyAdminService keys) {
		this.publishing = publishing;
		this.keys = keys;
	}

	@PostMapping("/admin/v1/language-datasets")
	DatasetListDto registerLanguageDatasets(@RequestBody(required = false) DatasetRegistrationRequest request,
			@AuthenticationPrincipal AuthenticatedUser user) {
		return publishing.registerDatasets(user, request);
	}

	@PostMapping(path = "/admin/v1/language-resources", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
	ResponseEntity<AdminLanguageResourceDto> uploadLanguageResource(
			@RequestParam(name = "datasetCode", required = false) List<String> datasetCodes, HttpServletRequest request,
			@AuthenticationPrincipal AuthenticatedUser user) throws IOException {
		AdminLanguageResourceDto created = publishing.upload(user, request.getInputStream(),
				request.getContentLengthLong(), datasetCodes);
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@GetMapping("/admin/v1/language-resources/{resourceId}")
	AdminLanguageResourceDto getAdminLanguageResource(@PathVariable UUID resourceId,
			@AuthenticationPrincipal AuthenticatedUser user) {
		return publishing.get(user, resourceId);
	}

	@PostMapping("/admin/v1/language-resources/{resourceId}/stage")
	AdminLanguageResourceDto stageLanguageResource(@PathVariable UUID resourceId,
			@AuthenticationPrincipal AuthenticatedUser user) {
		return publishing.stage(user, resourceId);
	}

	@PostMapping("/admin/v1/language-resources/{resourceId}/stable-approval")
	AdminLanguageResourceDto approveLanguageResourceForStable(@PathVariable UUID resourceId,
			@AuthenticationPrincipal AuthenticatedUser user) {
		return publishing.approveStable(user, resourceId);
	}

	@PostMapping("/admin/v1/language-resources/{resourceId}/publish")
	AdminLanguageResourceDto publishLanguageResource(@PathVariable UUID resourceId,
			@RequestBody(required = false) PublishRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
		return publishing.publish(user, resourceId, request);
	}

	@PostMapping("/admin/v1/language-resources/{resourceId}/retire")
	AdminLanguageResourceDto retireLanguageResource(@PathVariable UUID resourceId,
			@AuthenticationPrincipal AuthenticatedUser user) {
		return publishing.retire(user, resourceId);
	}

	@GetMapping("/admin/v1/pack-signer")
	PackSignerDto getPackSigner(@AuthenticationPrincipal AuthenticatedUser user) {
		return keys.signer(user);
	}

	@PostMapping("/admin/v1/resource-signing-keys")
	ResponseEntity<AdminSigningKeyDto> registerResourceSigningKey(
			@RequestBody(required = false) SigningKeyRegistration request,
			@AuthenticationPrincipal AuthenticatedUser user) {
		return ResponseEntity.status(HttpStatus.CREATED).body(keys.register(user, request));
	}

	@PostMapping("/admin/v1/resource-signing-keys/{keyId}/retire")
	AdminSigningKeyDto retireResourceSigningKey(@PathVariable String keyId,
			@AuthenticationPrincipal AuthenticatedUser user) {
		return keys.retire(user, keyId);
	}

	@PostMapping("/admin/v1/resource-signing-keys/{keyId}/revoke")
	AdminSigningKeyDto revokeResourceSigningKey(@PathVariable String keyId,
			@AuthenticationPrincipal AuthenticatedUser user) {
		return keys.revoke(user, keyId);
	}
}
