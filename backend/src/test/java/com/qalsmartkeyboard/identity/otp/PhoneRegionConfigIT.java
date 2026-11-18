package com.qalsmartkeyboard.identity.otp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** ADR 0007 D48: {@code qal.auth.phone.allowed-prefixes} widens phone sign-in without code changes. */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.auth.phone.allowed-prefixes=+2519,+2517,+4477" })
@AutoConfigureMockMvc
@Testcontainers
class PhoneRegionConfigIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	@Autowired
	MockMvc mvc;

	ResultActions start(String phone) throws Exception {
		String ip = "10." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(256)
				+ "." + ThreadLocalRandom.current().nextInt(1, 255);
		return mvc.perform(post("/api/v1/auth/otp/start").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"" + phone + "\"}"));
	}

	@Test
	void configuredPrefixesAreAcceptedAndOthersStillRefused() throws Exception {
		start("+2517" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)))
				.andExpect(status().isAccepted());
		start("+4477" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)))
				.andExpect(status().isAccepted());
		start("+12025550123").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PHONE_REGION_NOT_SUPPORTED"));
	}
}
