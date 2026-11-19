package com.qalsmartkeyboard.aigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.testsupport.contract.OpenApiContract;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;

/** ADR 0007 D32, D33, D35: the AI declarations of the contract batch stay in the contract. */
class AiContractDeclarationsTest {

	private static final OpenAPI API = OpenApiContract.load();

	private static Operation post(String path) {
		return API.getPaths().get(path).getPost();
	}

	@Test
	void everyAiOperationDeclaresWhatTheEndpointsCanAnswer() {
		assertThat(post("/api/v1/ai/translate").getResponses()).containsKeys("200", "400", "401", "403", "409", "422",
				"429", "503");
		assertThat(post("/api/v1/ai/transform").getResponses()).containsKeys("200", "400", "401", "403", "409", "422",
				"429", "503");
		// D33: detection is allowed for a pending deletion and refused for SUSPENDED, so it declares the 403
		Operation detect = post("/api/v1/ai/detect-language");
		assertThat(detect.getResponses()).containsKeys("200", "400", "401", "403", "422", "429", "503");
		assertThat(detect.getDescription()).contains("deletion is pending", "ACCOUNT_SUSPENDED",
				"ON_DEVICE_ONLY_PREFERRED");
		assertThat(post("/api/v1/ai/translate").getDescription()).contains("ON_DEVICE_ONLY_PREFERRED");
		assertThat(API.getPaths().get("/api/v1/ai/capabilities").getGet().getResponses()).containsKeys("200", "429");
	}

	/** ADR 0027: replies and drafts are served; they declare what the endpoints can answer. */
	@Test
	void repliesAndDraftsDeclareTheirAnswersAndTheTextActionOptions() {
		for (String path : List.of("/api/v1/ai/replies", "/api/v1/ai/drafts")) {
			assertThat(post(path).getResponses()).as(path).containsKeys("200", "400", "401", "403", "409", "422",
					"429", "503");
			assertThat(post(path).getParameters()).anyMatch(p -> "Idempotency-Key".equals(p.getName())
					|| (p.get$ref() != null && p.get$ref().endsWith("/IdempotencyKey")));
		}
		Map<String, Schema> schemas = API.getComponents().getSchemas();
		Map<String, Schema> draft = props("DraftRequest");
		assertThat(draft).containsKeys("documentType", "mode", "language", "instructions", "purpose", "keyPoints",
				"originalEmail", "draft", "tone", "variation", "recipient", "senderName", "length", "includeSubject",
				"saveToHistory");
		assertThat(schemas.get("DraftRequest").getRequired()).containsExactly("language");
		assertThat(draft.get("originalEmail").getMaxLength()).isEqualTo(8000);
		assertThat(draft.get("draft").getMaxLength()).isEqualTo(8000);
		assertThat(draft.get("instructions").getMaxLength()).isEqualTo(2000);
		assertThat(draft.get("mode").getEnum()).containsExactly("NEW", "REPLY", "IMPROVE");
		assertThat(schemas.get("EmailTone").getEnum()).containsExactly("PROFESSIONAL", "FORMAL", "FRIENDLY", "CASUAL",
				"POLITE", "PERSUASIVE", "APOLOGETIC", "CONCISE");
		assertThat(props("DraftResponse")).containsKeys("subject", "body", "placeholders", "mode", "language",
				"qualityTier", "usage", "replayed");
		Map<String, Schema> replies = props("RepliesRequest");
		assertThat(replies).containsKeys("contextText", "replyLanguage", "instructions", "variation", "count");
		assertThat(replies.get("contextText").getMaxLength()).isEqualTo(5000);
		assertThat(props("RepliesResponse")).containsKeys("suggestions", "language", "qualityTier", "usage");
		Map<String, Schema> transform = props("TransformRequest");
		assertThat(transform).containsKey("outputLanguage");
		Schema<?> options = transform.get("options");
		assertThat(String.valueOf(((Schema<?>) options.getProperties().get("mode")).getEnum())).contains("SIMPLIFY");
		assertThat(post("/api/v1/ai/transform").getDescription()).contains("SIMPLIFY", "PROFESSIONAL",
				"outputLanguage");
		assertThat(post("/api/v1/ai/drafts").getDescription()).contains("placeholders", "variation", "Re: ");
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static Map<String, Schema> props(String schema) {
		return API.getComponents().getSchemas().get(schema).getProperties();
	}

	@Test
	void idempotencyKeyDocumentsOldKeysReplaysAndCancelledOriginals() {
		String description = API.getComponents().getParameters().get("IdempotencyKey").getDescription();
		assertThat(description).contains("older than 24 h", "IDEMPOTENCY_KEY_REUSED", "original `requestId` and `usage`",
				"cancelled", "PROVIDER_UNAVAILABLE");
	}

	@Test
	void otpInvalidDocumentsAttemptsRemaining() {
		assertThat(API.getComponents().getSchemas().get("Problem").getDescription()).contains("attemptsRemaining",
				"OTP_INVALID");
		Operation verify = API.getPaths().get("/api/v1/auth/otp/verify").getPost();
		assertThat(verify.getDescription()).contains("attemptsRemaining", "deletion is pending");
	}

	@Test
	void aiNoteStatesThatConversionsAreCultural() {
		assertThat(API.getComponents().getSchemas().get("AiNote").getDescription()).contains("CULTURAL");
	}

	@Test
	void onlyThePartnerApiDeclaresTransliterate() {
		assertThat(API.getPaths().keySet()).noneMatch(p -> p.startsWith("/api/v1/ai/") && p.contains("transliterate"));
		assertThat(API.getPaths()).containsKey("/partner/v1/transliterate");
	}
}
