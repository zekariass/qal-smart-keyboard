package com.qalsmartkeyboard.aigateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.qalsmartkeyboard.aigateway.spi.Addressee;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.UserText;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the AI request bodies (contract {@code TranslateRequest}, {@code TransformRequest},
 * {@code DetectLanguageRequest}) by hand instead of letting the framework bind them.
 *
 * <p>Why: a framework binding error carries the rejected value in its message ("Cannot deserialize value of type
 * Formality from String "<i>the user's text</i>""), and the framework logs resolved exceptions, so a bad enum, UUID or
 * number inside a body that also holds the user's text could reach a log. Here every failure is a static
 * {@code VALIDATION_FAILED} naming only the field, never the value, and the Jackson exception is dropped. Syntax errors
 * are {@code MALFORMED_REQUEST_BODY}. Unknown members are ignored (the contract may grow).
 *
 * <p>{@code saveToHistory} is read here (B5.7) and only offered to the history sink by the pipeline. Parsed but not
 * acted on yet: {@code applyStyleProfile}
 * (PERSONAL_STYLE is a later step), {@code options.explainIn} and {@code options.sourceRomanization} (their tasks are
 * not served).
 */
final class AiRequestParser {

	private static final int MAX_CANDIDATES = 10;

	private final JsonMapper mapper;

	AiRequestParser(JsonMapper mapper) {
		this.mapper = mapper;
	}

	// ---------------------------------------------------------------------------------------------- requests

	AiCommand.Translate translate(byte[] body, AiCommand.Caller caller) {
		JsonNode n = root(body);
		String text = requiredText(n);
		return new AiCommand.Translate(caller, UserText.of(text), string(n, "sourceLanguage"),
				string(n, "destinationLanguage"), string(n, "style"), bool(n, "preserveNames"),
				uuid(n, "glossaryId"), addressee(n.get("addressee"), "addressee"),
				cultural(n.get("culturalOptions")), uuid(n, "destinationProfileId"),
				Boolean.TRUE.equals(bool(n, "saveToHistory")));
	}

	AiCommand.Transform transform(byte[] body, AiCommand.Caller caller, java.util.function.Function<String, AiFeature> tasks) {
		JsonNode n = root(body);
		String taskCode = string(n, "task");
		if (taskCode == null) {
			throw invalid("task", "REQUIRED");
		}
		AiFeature task = tasks.apply(taskCode);
		if (task == null) {
			throw invalid("task", "INVALID_VALUE");
		}
		String text = requiredText(n);
		JsonNode options = object(n.get("options"), "options");
		return new AiCommand.Transform(caller, task, UserText.of(text), string(n, "language"),
				options == null ? null : string(options, "options.mode", "mode"),
				options == null ? null : string(options, "options.tone", "tone"),
				options == null ? null : string(options, "options.targetLength", "targetLength"),
				addressee(n.get("addressee"), "addressee"), uuid(n, "glossaryId"),
				Boolean.TRUE.equals(bool(n, "saveToHistory")));
	}

	AiCommand.Detect detect(byte[] body, AiCommand.Caller caller) {
		JsonNode n = root(body);
		String text = requiredText(n);
		List<String> candidates = new ArrayList<>();
		JsonNode list = n.get("candidates");
		if (list != null && !list.isNull()) {
			if (!list.isArray()) {
				throw invalid("candidates", "INVALID_TYPE");
			}
			if (list.size() > MAX_CANDIDATES) {
				throw invalid("candidates", "TOO_MANY");
			}
			for (JsonNode c : list) {
				if (!c.isString()) {
					throw invalid("candidates", "INVALID_TYPE");
				}
				candidates.add(c.stringValue());
			}
		}
		return new AiCommand.Detect(caller, UserText.of(text), candidates);
	}

	// ---------------------------------------------------------------------------------------------- parts

	private JsonNode root(byte[] body) {
		JsonNode n;
		try {
			n = body == null || body.length == 0 ? null : mapper.readTree(body);
		} catch (RuntimeException e) {
			n = null; // the message may quote the body: drop it
		}
		if (n == null || !n.isObject()) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
					List.of(new ApiFieldError(null, "MALFORMED_REQUEST_BODY",
							"The request body is missing or is not valid JSON for this endpoint.")),
					Map.of());
		}
		return n;
	}

	private static String requiredText(JsonNode n) {
		String text = string(n, "text");
		if (text == null) {
			throw invalid("text", "REQUIRED");
		}
		return text;
	}

	private static String string(JsonNode n, String field) {
		return string(n, field, field);
	}

	private static String string(JsonNode n, String path, String member) {
		JsonNode v = n.get(member);
		if (v == null || v.isNull()) {
			return null;
		}
		if (!v.isString()) {
			throw invalid(path, "INVALID_TYPE");
		}
		return v.stringValue();
	}

	private static Boolean bool(JsonNode n, String field) {
		JsonNode v = n.get(field);
		if (v == null || v.isNull()) {
			return null;
		}
		if (!v.isBoolean()) {
			throw invalid(field, "INVALID_TYPE");
		}
		return v.booleanValue();
	}

	private static UUID uuid(JsonNode n, String field) {
		String s = string(n, field);
		if (s == null) {
			return null;
		}
		try {
			return UUID.fromString(s);
		} catch (IllegalArgumentException e) {
			throw invalid(field, "INVALID_FORMAT");
		}
	}

	private static JsonNode object(JsonNode v, String field) {
		if (v == null || v.isNull()) {
			return null;
		}
		if (!v.isObject()) {
			throw invalid(field, "INVALID_TYPE");
		}
		return v;
	}

	private static Addressee addressee(JsonNode v, String field) {
		JsonNode o = object(v, field);
		if (o == null) {
			return null;
		}
		return new Addressee(enumValue(o, field + ".formality", "formality", Addressee.Formality.class),
				enumValue(o, field + ".gender", "gender", Addressee.Gender.class));
	}

	private static AiCommand.CulturalOptions cultural(JsonNode v) {
		JsonNode o = object(v, "culturalOptions");
		if (o == null) {
			return null;
		}
		return new AiCommand.CulturalOptions(string(o, "culturalOptions.calendar", "calendar"),
				string(o, "culturalOptions.timeNotation", "timeNotation"),
				string(o, "culturalOptions.numerals", "numerals"), bool(o, "adaptIdioms"));
	}

	private static <E extends Enum<E>> E enumValue(JsonNode n, String path, String member, Class<E> type) {
		String s = string(n, path, member);
		if (s == null) {
			return null;
		}
		for (E e : type.getEnumConstants()) {
			if (e.name().equals(s)) {
				return e;
			}
		}
		throw invalid(path, "INVALID_VALUE");
	}

	/** Names the field and the kind of problem, never the value. */
	private static ApiException invalid(String field, String code) {
		String message = "REQUIRED".equals(code) ? field + " is required." : field + " is invalid.";
		return new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, message,
				List.of(new ApiFieldError(field, code, message)), Map.of());
	}
}
