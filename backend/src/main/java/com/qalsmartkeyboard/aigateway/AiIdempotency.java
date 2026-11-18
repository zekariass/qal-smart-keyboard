package com.qalsmartkeyboard.aigateway;

import java.util.UUID;

import com.qalsmartkeyboard.usage.Reservation;

/**
 * The idempotency seam of the pipeline (B5.3 builds the hook, B5.4 the encrypted replay cache; SD 11.6, contract
 * {@code IdempotencyKey}, ADR 0007 D25, plan rule N5).
 *
 * <p>{@link AiPipeline} reserves with the request's {@code Idempotency-Key}. When the metering engine says the key was
 * used before ({@link Reservation#replayed()}), nothing new was reserved and the pipeline asks {@link #onReplay} what
 * to do instead of calling a provider and charging again. After a successful, settled request it hands the response
 * to {@link #onCompleted} so an implementation can keep it (encrypted, at most 10 minutes) for a later replay.
 *
 * <p>Right after a <b>new</b> reservation (before any provider call) the pipeline calls {@link #onReserved}, so an
 * implementation can remember what the key was used for and recognise the same key with another body later, even
 * while the original is still in flight.
 *
 * <p>B5.3's {@code pipeline.ReplayWithoutCache} has no cache: in flight = {@code REQUEST_IN_PROGRESS}; settled =
 * recompute without a charge; released = the original failure again (D25). The default bean since B5.4 is
 * {@code idempotency.EncryptedReplayCache} (request fingerprints and the encrypted 10-minute replay cache, ADR 0017
 * "Replay cache (B5.4)"); it is {@code @ConditionalOnMissingBean}.
 */
public interface AiIdempotency {

	/** What the pipeline does with a replayed key. */
	sealed interface Decision permits Decision.Respond, Decision.Recompute, Decision.Fail {

		/** Answer with this response (for example from the replay cache, {@code replayed = true}); no provider call. */
		record Respond(AiResult result) implements Decision {
		}

		/** Run the provider call again without metering ({@code usageSource = UNMETERED}, never a second charge). */
		record Recompute() implements Decision {
		}

		/** Answer with this Problem (REQUEST_IN_PROGRESS, the original failure...). */
		record Fail(RuntimeException problem) implements Decision {
		}
	}

	/**
	 * @param reservation the original reservation, with its current status (RESERVED = still in flight, SETTLED =
	 *                    charged once already, RELEASED = failed or cancelled without a charge)
	 */
	Decision onReplay(AiCommand command, Reservation reservation);

	/**
	 * A metered request reserved with a key never used before; no provider was called yet. Called outside a
	 * transaction. An exception is logged and the request goes on (the reservation already guarantees at-most-once
	 * charging). Default: keep nothing.
	 */
	default void onReserved(AiCommand command, Reservation reservation) {
	}

	/** A metered request succeeded and was settled; {@code requestId} = the reservation id. Default: keep nothing. */
	default void onCompleted(AiCommand command, UUID requestId, AiResult result) {
	}
}
