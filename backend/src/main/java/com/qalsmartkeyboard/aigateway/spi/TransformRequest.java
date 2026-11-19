package com.qalsmartkeyboard.aigateway.spi;

import java.util.List;
import java.util.Objects;

/**
 * Grammar, rewrite, tone, summarise, explain, reply suggestions and e-mail drafting through an LLM; maps to the
 * contract's {@code TransformRequest}, {@code RepliesRequest} and {@code DraftRequest}. The pipeline renders the
 * versioned prompt template (B5.3) into {@code instructions}; the adapter sends {@code text} and every
 * {@code context} part as delimited, untrusted data next to it and gives the model no tools.
 *
 * <p>What {@code text} is depends on the task (ADR 0027): the text to work on (transforms, summarise, explain), the
 * message being answered (SMART_REPLY), and for DRAFT_DOCUMENT the user's description of the e-mail (mode NEW), the
 * e-mail being answered (REPLY) or the draft to improve (IMPROVE). Further user content (the user's notes on what to
 * say, names) travels in {@code context}, one {@link ContextPart} each, never inside {@code instructions}.
 *
 * @param language       language of {@code text}; null = unknown
 * @param mode           contract {@code options.mode} (GRAMMAR, NATURAL, SHORTEN, SIMPLIFY...) or the draft mode
 *                       (NEW, REPLY, IMPROVE); null = none
 * @param tone           contract {@code options.tone}, the reply tone or the e-mail tone; null = none
 * @param targetLength   contract {@code options.targetLength} or the draft {@code length}; null = none
 * @param outputLanguage language the result must be written in; null = the language of {@code text}
 * @param instructions   rendered template (never user content); null for adapters with built-in prompts
 * @param context        further user content, in order; empty for most tasks
 * @param count          SMART_REPLY: how many alternative replies (1..5); 0 for every other task
 * @param variation      0 = first answer; a higher number asks for a different version of the same request
 *                       (regenerate)
 */
public record TransformRequest(AiFeature task, UserText text, String language, String mode, String tone,
		String targetLength, String outputLanguage, Addressee addressee, String instructions, List<ContextPart> context,
		int count, int variation) {

	public TransformRequest {
		Objects.requireNonNull(task, "task");
		Objects.requireNonNull(text, "text");
		if (task.operation() != AiFeature.Operation.TRANSFORM) {
			throw new IllegalArgumentException("not a transform feature: " + task);
		}
		if (count < 0 || variation < 0) {
			throw new IllegalArgumentException("negative count or variation");
		}
		addressee = addressee == null ? Addressee.UNSPECIFIED : addressee;
		context = context == null ? List.of() : List.copyOf(context);
	}

	/** A single-text request (the B5.1 shape). */
	public TransformRequest(AiFeature task, UserText text, String language, String mode, String tone,
			String targetLength, String outputLanguage, Addressee addressee, String instructions) {
		this(task, text, language, mode, tone, targetLength, outputLanguage, addressee, instructions, List.of(), 0, 0);
	}

	public static TransformRequest of(AiFeature task, String text, String language) {
		return new TransformRequest(task, UserText.of(text), language, null, null, null, null, null, null);
	}

	/** Code points of {@code text} and every context part: what the provider is sent. */
	public int inputCharacters() {
		int n = text.characters();
		for (ContextPart p : context) {
			n += p.text().characters();
		}
		return n;
	}

	/** The same request with the rendered instructions for one provider. */
	public TransformRequest withInstructions(String rendered) {
		return new TransformRequest(task, text, language, mode, tone, targetLength, outputLanguage, addressee, rendered,
				context, count, variation);
	}
}
