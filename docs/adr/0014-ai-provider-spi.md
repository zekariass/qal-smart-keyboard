# ADR 0014 — AI provider adapter SPI, registry and data policy

**Status:** Accepted · **Date:** 2026-11-18 · **Plan step:** B5.1 (B5.2–B5.5 build on it) · **Spec:** SD §11.1–11.4 (gateway,
providers, data policy, capability matrix, guardrails), §13 (organisation data policy, ZDR), §17/§19 (timeouts, logging
hygiene: never log user text, prompts or provider raw errors); contract `TranslateCoreRequest`, `TransformRequest`,
`DetectLanguageRequest`, their responses, `AiCapabilities`, `QualityTier`, `Problem.code`; V1 `ai_providers`,
`ai_models`, `ai_language_capabilities`, `ai_routing_policies`; ADR 0011 (metering), ADR 0001 OI-R1/OI-P5, human gate G0.

## Context
The pipeline (B5.3) must call translation, LLM and language-identification vendors without knowing which one, route by
language pair and data policy (B5.2), and settle metering with what the vendor actually processed (ADR 0011). The vendor
shortlist is **not decided** (gate G0), so this step delivers the contract, the registry and the mocks, and no vendor.

## Decision

### SPI (`com.qalsmartkeyboard.aigateway.spi`, a Modulith named interface)
`AiProvider` is one bean per vendor: `code()` (= `ai_providers.provider_code`), `translate(TranslateRequest, Duration)`,
`llmTransform(TransformRequest, Duration)`, `detectLanguage(DetectRequest, Duration)`. Defaults throw
`UnsupportedOperationException` (the registry never routes an undeclared operation). Requests and results mirror the
contract schemas (`TranslateCoreRequest` → `TranslateRequest`, `TransformRequest` with `AiFeature` task, mode, tone,
target length, explain-in, addressee, rendered `instructions`; results carry text, detected language/confidence,
alternatives, edits, ranked languages, `romanizedNative`). The pipeline adds `requestId`, `qualityTier`, `replayed`,
`usage`. `AiFeature` names equal `feature_catalog.feature_code`; voice features (V5.1, D6.4) are outside this SPI and will
add an `AiProvider` operation later.

- **Timeouts and cancellation:** every call takes a positive `Duration` (hard deadline for the whole call); there is no
  untimed overload. Cancellation is thread interruption (virtual threads): the adapter restores the flag and throws
  `Cancelled`. No retries inside an adapter; retry and failover are the router's (B5.2).
- **Usage:** `ProviderUsage` = input/output characters (always measured by us, code points), optional provider input/output
  tokens and audio milliseconds; `units(MeteredUnit)` yields REQUEST = 1, CHARACTER = input characters, SECOND = audio
  seconds rounded up (ADR 0011 settle units). `ProviderCallMetadata` = provider code, model code, vendor request id,
  latency (feeds `ai_requests.duration_ms`, provider and model ids).
- **Content hygiene:** all text crosses the SPI as `UserText`, whose `toString()` prints a length only, so accidental
  logging of a request, result or exception cannot leak content. Exception messages are fixed strings, the cause chain is
  never a provider response, and HTTP error bodies are never read.

### Error taxonomy (`ProviderException`, sealed; `Kind` carries the defaults)
| Exception | `Kind` → contract code | retryable | counts against provider health |
|---|---|---|---|
| `Unavailable` | `PROVIDER_UNAVAILABLE` | yes | yes |
| `Timeout` | `PROVIDER_TIMEOUT` | yes | yes |
| `ContentRejected` | `CONTENT_REJECTED` | no | no |
| `UnsupportedLanguagePair` | `LANGUAGE_PAIR_UNAVAILABLE` | no | no |
| `QuotaExhaustedAtProvider` (our account, `retryAfter`) | `PROVIDER_UNAVAILABLE` | no | yes (ops alert) |
| `AuthenticationFailed` (our credential) | `PROVIDER_UNAVAILABLE` | no | yes (page) |
| `InvalidResponse` (malformed, empty, truncated) | `PROVIDER_UNAVAILABLE` | yes | yes |
| `Cancelled` | none (not client visible) | no | no |

The contract has no code for "provider quota" or "bad credential": clients see `PROVIDER_UNAVAILABLE` and no charge
(contract 503 text); the distinction is for routing and alerting only. `ZERO_RETENTION_UNAVAILABLE` is not an adapter
outcome: the router raises it when the registry returns no provider for a ZDR requirement (B5.2/B5.3).

### Descriptor, data policy and registry
`ProviderDescriptor` is read from `ai_providers` + SERVER models with `model_status = ACTIVE` + enabled
`ai_language_capabilities` (`JdbcProviderDescriptorSource`; the snapshot is cached `qal.ai.registry-cache-ttl`, 30 s, stale
snapshot served if a reload fails, like `/config`). `DataPolicy` = `zero_data_retention`, `trains_on_customer_data`,
`processing_regions`, `dpa_signed_at IS NOT NULL`. A capability = feature, source and destination language (null = any),
quality tier, auto-detection, max input characters, model code.

`ProviderRegistry.resolve(RouteQuery)` returns a provider only if **all** hold:
1. status is `ACTIVE`, `trains_on_customer_data` is FALSE and, unless `SELF_HOSTED`, a DPA is signed (the V1 CHECK says
   the same; repeated so a hand-edited row cannot slip through). `DEGRADED` and `DISABLED` are excluded; B5.2 may admit
   DEGRADED as a last resort.
2. an adapter bean with the same code exists: a row alone never creates an integration, an adapter alone is never used.
3. the data policy satisfies the query: a ZDR requirement (BUSINESS / zero-retention organisations) needs
   `zero_data_retention`; a region allow-list needs every declared region inside it and fails closed for a provider
   that declares none. Training on customer data is refused unconditionally.
4. an enabled capability matches: feature; source (null query source = auto-detect, needs `supports_auto_detection`
   except for language identification); destination; tier at least the query minimum (default BETA: only BETA and
   PRODUCTION reach users, SD 11.2); input size within `max_input_characters`.
Result order is by provider code and means nothing: priority, weights, fallback, circuit breaker, kill switch and cost
caps are B5.2 over `ai_routing_policies`. Quality-equivalent fallback (SD 11.1) can compare `ResolvedProvider.capability()`.

### Providers seeded inactive (V10)
The plan's "inactive" is `provider_status = 'DISABLED'` in V1 (no INACTIVE value exists; not changed). V10 seeds only
`mock` and `wiremock-http`, both DISABLED, ZDR/no-training TRUE (data stays local), no DPA, no `secret_reference`, models
ACTIVE so the provider flag is the single gate, capabilities BETA (dev convenience: TRANSLATE en↔am/ti/om, grammar/
rewrite/tone for am/ti/om/en, DETECT_LANGUAGE). The adapter beans exist only when `qal.ai.mock.enabled` /
`qal.ai.http.enabled` is set and the application refuses both under prod/production/staging, so even an enabled row has
no route in production. No real vendor row is seeded: each is added with its adapter after G0.

### Adapters in this step
- **`MockAiProvider`** (dev/test): deterministic output (`[mock:<dst>] <text>`; grammar collapses whitespace; script-based
  language detection), tokens estimated 1 per 4 characters, configurable latency, failure mode (all taxonomy kinds) and
  failing-call count (`failingFirst(n)`) switchable at runtime, call counter, configurable `code` so B5.2 tests can run a
  primary and a fallback mock. A latency at or above the timeout waits only the timeout and throws `Timeout`.
- **`HttpJsonAiProvider`** (dev/test, skeleton): Spring `RestClient` on a shared JDK `HttpClient` (HTTP/1.1, no
  redirects, connect timeout from config, read timeout = call timeout), bearer key from `QAL_AI_HTTP_API_KEY`, optional
  `X-Mock-Scenario`. Speaks the **placeholder** JSON dialect of the compose stubs (`/ai/v1/translate`,
  `/ai/v1/llm/chat`, `/ai/v1/detect-language`; OI-P5). Status mapping: 401/403 AuthenticationFailed, 402/429
  QuotaExhaustedAtProvider (numeric `Retry-After`), 408/504 Timeout, 422/451 ContentRejected, other 4xx/5xx Unavailable;
  I/O timeout Timeout, connection failure Unavailable, interrupt Cancelled, undecodable/incomplete JSON or LLM
  `finish_reason` other than `stop` InvalidResponse (`content_filter` → ContentRejected). Logs nothing.
  A real vendor adapter copies the mechanics, not the wire format.

### Secrets
API keys come from configuration/environment only (never `ai_providers.configuration`, never logs); the column
`secret_reference` stays a reference for the secret manager (SD 19) that a vendor adapter resolves. Property records
redact keys in `toString`.

## Consequences
- B5.2 uses `resolve` plus `ProviderException.Kind` for fallback/breaker accounting; B5.3 maps `Kind.contractCode()` to
  `ApiException` and settles with `ProviderUsage.units(...)`; B5.5 writes `ai_requests` from `ProviderCallMetadata`.
- ADR 0001 OI-R1/OI-P5 stay open: the shortlist, real vendor wire formats, per-vendor ZDR/regions and recorded fixtures
  ("adapter contract tests with recorded fixtures", plan B5.1) can only be done per vendor after G0. The WireMock tests
  run the compose stubs in-process (`wiremock-standalone`, test scope) and cover success, slow, error and every status.
- Open: `DEGRADED` routing; whether `Cancelled` should surface as a client-abort outcome; voice (ASR/TTS) operations;
  adding INACTIVE to the status vocabulary (not needed).
