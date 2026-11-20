package com.qalsmartkeyboard.billing.chapa;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.qalsmartkeyboard.billing.spi.CheckoutRequest;
import com.qalsmartkeyboard.billing.spi.CheckoutSession;
import com.qalsmartkeyboard.billing.spi.MerchantAccount;
import com.qalsmartkeyboard.billing.spi.Money;
import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;
import com.qalsmartkeyboard.billing.spi.ProviderPaymentStatus;
import com.qalsmartkeyboard.billing.spi.VerificationRequest;
import com.qalsmartkeyboard.billing.spi.VerificationResult;

/**
 * OPT-IN live smoke test against Chapa's test mode (ADR 0045 step A2). Never runs in CI or a normal {@code verify}: it
 * needs {@code CHAPA_SECRET_KEY} (a TEST key, {@code CHASECK_TEST-...}) and {@code QAL_LIVE_SANDBOX=1}. It initializes a
 * real test transaction through the adapter (expects an https checkout URL), verifies it (expects an unpaid state:
 * NOT_STARTED or PENDING) and checks that a second initialize with the same {@code tx_ref} is refused. Paying needs the
 * hosted page (documented test cards / phones), which this test does not drive. Asserts statuses and shapes
 * only; never prints the key or response bodies.
 *
 * <pre>
 * set -a; . &lt;(tr -d '\r' &lt; ../infra/scripts/.dev-secrets.env | grep -E '^[A-Z_]+=.+'); set +a
 * QAL_LIVE_SANDBOX=1 ./mvnw -B -ntp -Djava.version=21 -Dit.test=ChapaLiveSandboxIT -Dtest=NONE \
 *     -Dsurefire.failIfNoSpecifiedTests=false verify
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "CHAPA_SECRET_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "QAL_LIVE_SANDBOX", matches = "1")
class ChapaLiveSandboxIT {

	@Test
	void initializesAndVerifiesARealTestTransaction() {
		String key = System.getenv("CHAPA_SECRET_KEY");
		assertThat(key).as("a TEST key is required").startsWith("CHASECK_TEST");
		ChapaProvider provider = new ChapaProvider(new ChapaProperties(key, null, PaymentEnvironment.SANDBOX, null,
				Duration.ofSeconds(10), Duration.ofSeconds(30), "Qal Keyboard", true));
		MerchantAccount account = new MerchantAccount("CHAPA", "chapa-et-sandbox", "ET", "ETB",
				PaymentEnvironment.SANDBOX, null);
		String txRef = UUID.randomUUID().toString();
		Money amount = new Money(29900, "ETB", 2);
		URI ret = URI.create("https://example.invalid/api/v1/billing/return/" + txRef);
		CheckoutRequest request = new CheckoutRequest(account, txRef, "QAL-ET-SMOKE", amount, "Premium (30 days)",
				"CHAPA_CHECKOUT", null, URI.create(ret + "?result=success"), URI.create(ret + "?result=cancel"),
				URI.create(ret + "?result=error"),
				URI.create("https://example.invalid/api/v1/billing/webhooks/CHAPA/chapa-et-sandbox"),
				Instant.now().plus(Duration.ofMinutes(30)), "en");

		CheckoutSession session = provider.initiateCheckout(request);
		assertThat(session.providerReference()).isEqualTo(txRef);
		assertThat(session.checkoutUrl().getScheme()).isEqualTo("https");

		VerificationResult before = provider.verify(new VerificationRequest(account, txRef, txRef, amount));
		assertThat(before.status()).isIn(EnumSet.of(ProviderPaymentStatus.NOT_STARTED, ProviderPaymentStatus.PENDING));

		try {
			provider.initiateCheckout(request);
			throw new AssertionError("a reused tx_ref must be refused");
		}
		catch (com.qalsmartkeyboard.billing.spi.PaymentProviderException e) {
			assertThat(e.safeCode()).isEqualTo("DUPLICATE_TX_REF");
		}
		System.out.println("Chapa live test-mode smoke: initialized, verified " + before.status()
				+ ", duplicate tx_ref refused");
	}
}
