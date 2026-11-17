package com.qalsmartkeyboard.devices;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.qalsmartkeyboard.devices.DeviceDtos.DeviceDto;
import com.qalsmartkeyboard.identity.DeviceInfo;
import com.qalsmartkeyboard.identity.security.ApiSecurityConfiguration;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.web.ServletProblemResponseWriter;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * B2.5: {@code registerDevice}, {@code updateDevice} and {@code revokeDevice} against the contract (service mocked),
 * behind the real security chain. The contract declares 401 for {@code registerDevice} only, so the other operations'
 * 401 is covered by {@link DevicesIT}.
 */
@WebMvcTest(DeviceController.class)
@ContractValidated
@Import({ ApiSecurityConfiguration.class, ServletProblemResponseWriter.class })
class DeviceControllerTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	AccessTokenService tokens;
	@MockitoBean
	DeviceService service;

	final UUID userId = UUID.randomUUID();
	final UUID sessionId = UUID.randomUUID();
	final UUID installation = UUID.randomUUID();
	final UUID deviceId = UUID.randomUUID();

	String bearer() {
		return "Bearer " + tokens.issue(userId, sessionId, null, "REGISTERED").value();
	}

	DeviceDto device(String appVersion) {
		return new DeviceDto(deviceId, installation, "ANDROID", "Pixel 8", appVersion, "Android 15", "ACTIVE",
				"NOT_CHECKED", Instant.parse("2026-11-17T10:00:00Z"), Instant.parse("2026-11-17T09:00:00Z"));
	}

	@Test
	void registerReturns200DeviceWithoutPushToken() throws Exception {
		when(service.register(any(), any())).thenReturn(device("2.0.3"));
		mvc.perform(post("/api/v1/devices").header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"installationId\":\"" + installation + "\",\"platform\":\"ANDROID\","
						+ "\"deviceName\":\"Pixel 8\",\"appVersion\":\"2.0.3\",\"osVersion\":\"Android 15\","
						+ "\"pushProvider\":\"FCM\",\"pushToken\":\"fcm-secret-token\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(deviceId.toString()))
				.andExpect(jsonPath("$.installationId").value(installation.toString()))
				.andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.attestationStatus").value("NOT_CHECKED"))
				.andExpect(content().string(not(containsString("fcm-secret-token"))))
				.andExpect(content().string(not(containsString("pushToken"))));
		ArgumentCaptor<DeviceInfo> info = ArgumentCaptor.forClass(DeviceInfo.class);
		verify(service).register(any(), info.capture());
		org.assertj.core.api.Assertions.assertThat(info.getValue().pushToken()).isEqualTo("fcm-secret-token");
	}

	@Test
	void registerTakesTheAppVersionFromTheHeaderWhenTheBodyHasNone() throws Exception {
		when(service.register(any(), any())).thenReturn(device("2.1.0"));
		mvc.perform(post("/api/v1/devices").header("Authorization", bearer()).header("X-Client-Platform", "ANDROID")
				.header("X-App-Version", "2.1.0").contentType(MediaType.APPLICATION_JSON)
				.content("{\"installationId\":\"" + installation + "\",\"platform\":\"ANDROID\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.appVersion").value("2.1.0"));
		ArgumentCaptor<DeviceInfo> info = ArgumentCaptor.forClass(DeviceInfo.class);
		verify(service).register(any(), info.capture());
		org.assertj.core.api.Assertions.assertThat(info.getValue().appVersion()).isEqualTo("2.1.0");
	}

	@Test
	void registerWithAPlatformThatContradictsTheHeaderIs400() throws Exception {
		mvc.perform(post("/api/v1/devices").header("Authorization", bearer()).header("X-Client-Platform", "IOS")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"installationId\":\"" + installation + "\",\"platform\":\"ANDROID\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("platform"));
		verify(service, never()).register(any(), any());
	}

	@Test
	void registerRejectsAMalformedBodyWith400() throws Exception {
		mvc.perform(post("/api/v1/devices").header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"installationId\":\"not-a-uuid\",\"platform\":\"ANDROID\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(content().string(not(containsString("not-a-uuid"))));
		mvc.perform(post("/api/v1/devices").header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"installationId\":\"" + installation + "\",\"platform\":\"WINDOWS\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("platform"));
		mvc.perform(post("/api/v1/devices").header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"platform\":\"ANDROID\"}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("installationId"));
	}

	@Test
	void registerWithoutATokenIs401Problem() throws Exception {
		mvc.perform(post("/api/v1/devices").contentType(MediaType.APPLICATION_JSON)
				.content("{\"installationId\":\"" + installation + "\",\"platform\":\"ANDROID\"}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		verify(service, never()).register(any(), any());
	}

	@Test
	void updateReturns200AndPassesTheCallerAndTheHeaderVersion() throws Exception {
		when(service.update(eq(userId), eq(deviceId), any())).thenReturn(device("2.2.0"));
		mvc.perform(patch("/api/v1/devices/" + deviceId).header("Authorization", bearer())
				.header("X-App-Version", "2.2.0").contentType(MediaType.APPLICATION_JSON)
				.content("{\"osVersion\":\"Android 15\",\"pushProvider\":\"FCM\",\"pushToken\":\"tok-1\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.appVersion").value("2.2.0"))
				.andExpect(content().string(not(containsString("tok-1"))));
		ArgumentCaptor<DeviceInfo> changes = ArgumentCaptor.forClass(DeviceInfo.class);
		verify(service).update(eq(userId), eq(deviceId), changes.capture());
		org.assertj.core.api.Assertions.assertThat(changes.getValue().appVersion()).isEqualTo("2.2.0");
		org.assertj.core.api.Assertions.assertThat(changes.getValue().pushToken()).isEqualTo("tok-1");
	}

	@Test
	void updateOfAnUnknownOrForeignDeviceIs404Problem() throws Exception {
		when(service.update(any(), any(), any())).thenThrow(DeviceService.notFound());
		mvc.perform(patch("/api/v1/devices/" + deviceId).header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND")).andExpect(jsonPath("$.correlationId").exists());
	}

	@Test
	void updateRejectsAnInvalidPushProviderWith400() throws Exception {
		mvc.perform(patch("/api/v1/devices/" + deviceId).header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"pushProvider\":\"SMOKE_SIGNAL\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("pushProvider"));
	}

	@Test
	void revokeReturns204AndUnknownIs404() throws Exception {
		mvc.perform(delete("/api/v1/devices/" + deviceId).header("Authorization", bearer()))
				.andExpect(status().isNoContent());
		verify(service).revoke(userId, deviceId);
		UUID other = UUID.randomUUID();
		doThrow(DeviceService.notFound()).when(service).revoke(userId, other);
		mvc.perform(delete("/api/v1/devices/" + other).header("Authorization", bearer()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}
}
