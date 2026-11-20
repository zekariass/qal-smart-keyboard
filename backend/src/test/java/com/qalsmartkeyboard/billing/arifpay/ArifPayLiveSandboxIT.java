package com.qalsmartkeyboard.billing.arifpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
 * OPT-IN live smoke test against the real ArifPay sandbox (ADR 0045). Never runs in CI or a normal {@code verify}: it
 * needs both {@code ARIFPAY_SECRET_KEY} (a sandbox key) and {@code QAL_LIVE_SANDBOX=1}. It creates a real sandbox
 * checkout session through the adapter, verifies it (expects NOT_STARTED), pays it with the sandbox simulator
 * ({@code /api/sandbox/c2b/telebirr-ussd/transfer}, sim mode SUCCESS, the documented test phone) and verifies again
 * (expects SUCCEEDED with the order amount, our nonce and a transaction id). Asserts statuses and shapes only; never
 * prints the key or response bodies.
 *
 * <pre>
 * set -a; . &lt;(tr -d '\r' &lt; ../infra/scripts/.dev-secrets.env | grep -E '^[A-Z_]+=.+'); set +a
 * QAL_LIVE_SANDBOX=1 ./mvnw -B -ntp -Djava.version=21 -Dit.test=ArifPayLiveSandboxIT -Dtest=NONE \
 *     -Dsurefire.failIfNoSpecifiedTests=false verify
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "ARIFPAY_SECRET_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "QAL_LIVE_SANDBOX", matches = "1")
class ArifPayLiveSandboxIT {

	@Test
	void createsVerifiesPaysAndVerifiesARealSandboxSession() throws Exception {
		String key = System.getenv("ARIFPAY_SECRET_KEY");
		ArifPayProperties props = new ArifPayProperties(key, PaymentEnvironment.SANDBOX, null, null,
				Duration.ofSeconds(10), Duration.ofSeconds(30), null, null, null, ZoneId.of("Africa/Addis_Ababa"), "EN");
		ArifPayProvider provider = new ArifPayProvider(props, "live-sandbox-smoke-test-key-32-bytes!!".getBytes());
		MerchantAccount account = new MerchantAccount("ARIFPAY", "arifpay-et-sandbox", "ET", "ETB",
				PaymentEnvironment.SANDBOX, null);
		String nonce = UUID.randomUUID().toString();
		Money amount = new Money(29900, "ETB", 2);
		URI ret = URI.create("https://example.invalid/api/v1/billing/return/" + nonce);

		CheckoutSession session = provider.initiateCheckout(new CheckoutRequest(account, nonce, "QAL-ET-SMOKE",
				amount, "Premium (30 days)", "ARIFPAY_CHECKOUT", null, URI.create(ret + "?result=success"),
				URI.create(ret + "?result=cancel"), URI.create(ret + "?result=error"),
				URI.create("https://example.invalid/api/v1/billing/webhooks/ARIFPAY/arifpay-et-sandbox"),
				Instant.now().plus(Duration.ofMinutes(30)), "en"));
		assertThat(session.providerReference()).matches("[A-Za-z0-9]{6,64}");
		assertThat(session.checkoutUrl().getScheme()).isEqualTo("https");

		VerificationResult before = provider.verify(new VerificationRequest(account, session.providerReference(),
				nonce, amount));
		assertThat(before.status()).isEqualTo(ProviderPaymentStatus.NOT_STARTED);
		assertThat(before.merchantReference()).isEqualTo(nonce);
		assertThat(before.providerReference()).isEqualTo(session.providerReference());

		HttpResponse<Void> pay = HttpClient.newHttpClient().send(HttpRequest
				.newBuilder(URI.create("https://gateway.arifpay.org/api/sandbox/c2b/telebirr-ussd/transfer"))
				.header("Content-Type", "application/json").header("x-arifpay-key", key)
				.header("x-arifpay-sim-mode", "SUCCESS").timeout(Duration.ofSeconds(60))
				.POST(HttpRequest.BodyPublishers.ofString("{\"sessionId\":\"" + session.providerReference()
						+ "\",\"phoneNumber\":\"251900000000\"}"))
				.build(), HttpResponse.BodyHandlers.discarding());
		assertThat(pay.statusCode()).isEqualTo(200);

		VerificationResult after = provider.verify(new VerificationRequest(account, session.providerReference(), nonce,
				amount));
		assertThat(after.status()).isEqualTo(ProviderPaymentStatus.SUCCEEDED);
		assertThat(after.amount()).isEqualTo(amount);
		assertThat(after.merchantReference()).isEqualTo(nonce);
		assertThat(after.providerTransactionReference()).isNotBlank();
		assertThat(after.providerMethodCode()).isEqualTo("TELEBIRR_USSD");
		System.out.println("ArifPay live sandbox smoke: session created, NOT_STARTED -> SUCCEEDED");
	}
}
