package com.qalsmartkeyboard.aigateway.adapter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.ChatTurn;
import com.qalsmartkeyboard.aigateway.spi.ContextPart;
import com.qalsmartkeyboard.aigateway.spi.TransformRequest;
import com.qalsmartkeyboard.aigateway.spi.UserText;

/** ADR 0048: the generic HTTP adapter sends the chat turns as a {@code messages} list and reads {@code length}. */
class HttpJsonAiProviderChatTest {

	static WireMockServer server;

	@BeforeAll
	static void start() {
		server = new WireMockServer(options().dynamicPort());
		server.start();
	}

	@AfterAll
	static void stop() {
		server.stop();
	}

	@Test
	void theTurnsTravelInOrderWithTheirRolesAndALengthStopIsATruncatedReply() {
		server.stubFor(post(urlPathEqualTo("/ai/v1/llm/chat")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json")
				.withBody("{\"output\":\"half an ans\",\"finish_reason\":\"length\",\"model\":\"m\"}")));
		var provider = new HttpJsonAiProvider(new HttpJsonAiProvider.Config("wiremock-http",
				URI.create(server.baseUrl()), Duration.ofSeconds(1), "key", ""));
		var turns = List.of(ChatTurn.user("Hello"), ChatTurn.assistant("Hi"), ChatTurn.user("Go on"));
		var request = new TransformRequest(AiFeature.AI_CHAT, UserText.of("Go on"), null, null, null, null, "am", null,
				"Rendered.", List.of(ContextPart.of(ContextPart.Kind.FIELD_TEXT, "my field")), 0, 0, turns);

		var result = provider.llmTransform(request, Duration.ofSeconds(3));

		assertThat(result.truncated()).isTrue();
		assertThat(result.resultText().value()).isEqualTo("half an ans");
		server.verify(postRequestedFor(urlPathEqualTo("/ai/v1/llm/chat"))
				.withRequestBody(matchingJsonPath("$.task", equalTo("AI_CHAT")))
				.withRequestBody(matchingJsonPath("$.messages.length()", equalTo("3")))
				.withRequestBody(matchingJsonPath("$.messages[1].role", equalTo("ASSISTANT")))
				.withRequestBody(matchingJsonPath("$.messages[1].text", equalTo("Hi")))
				.withRequestBody(matchingJsonPath("$.messages[2].text", equalTo("Go on")))
				.withRequestBody(matchingJsonPath("$.context[0].kind", equalTo("FIELD_TEXT")))
				.withRequestBody(matchingJsonPath("$.output_language", equalTo("am"))));
	}
}
