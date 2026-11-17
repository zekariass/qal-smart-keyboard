# language-data/eval - provider evaluation harness (plan step F0.6)

Runs test sets for Amharic (`am`), Tigrinya (`ti`), Afaan Oromoo (`om`) and English (`en`) through candidate
MT, LLM and ASR providers and writes one CSV row per provider x test set x direction. Python 3.11+, standard
library only. Works against the local WireMock mocks now; real providers plug in by config + one adapter module.

Feeds: human gate **G0** (provider shortlist), plan step **B5.1** (capability matrix / routing), design v2.0
section 11.8 (evaluation runs) and release gate **R2** (section 21.5: every advertised feature x pair needs an
evaluation run meeting its tier threshold).

## Run against the mocks

```bash
docker compose -f infra/compose/docker-compose.yml up -d wiremock
cd language-data/eval
python -m qal_eval run --config configs/local-mock.json --out results.csv [--items-out items.jsonl]
docker compose -f ../../infra/compose/docker-compose.yml stop wiremock
python -m unittest discover -s tests          # unit tests, no Docker needed
```

`configs/local-mock-error.json` sends `X-Mock-Scenario: error` / `slow` to show that failures are counted, not fatal.
The mocks return fixed sample sentences, so the scores are meaningless; only the plumbing is being exercised.
Exit code is non-zero (2) only for config errors; provider errors and timeouts are counted in the CSV.

## Config (JSON)

```json
{
  "providers": [{"name": "...", "kind": "mt|llm|asr", "adapter": "mock_http", "base_url": "...",
                 "api_key_env": "ENV_VAR_NAME", "timeout_s": 20, "model": "optional", "headers": {}}],
  "testsets":  [{"name": "...", "path": "relative/to/config", "task": "translation|asr"}],
  "language_pairs": ["en-am", "am-en"]
}
```

API keys are read from the environment variable named in `api_key_env`; a literal `api_key` field is rejected.
`language_pairs` is an optional filter (`src-tgt`; for ASR use `am-am`). The CSV `config_hash` is a short SHA-256 of
the config JSON. See `configs/example-real.json` (placeholders only).

## Adding a provider adapter

1. Create `qal_eval/adapters/<name>.py`, subclass `Adapter` (`adapters/base.py`), decorate with `@register("<name>")`.
2. Implement `translate(text, source, target) -> str` (kinds `mt`, `llm`) and/or `transcribe(audio_path, language) -> str`
   (kind `asr`). Raise `AdapterError(msg, timeout=True/False)` on failure; use `self.timeout_s`, `self.api_key`, `self.model`.
3. Reference it in a config via `"adapter": "<name>"`. Modules are auto-discovered; no other registration needed.
4. Add a test with an in-process fake `http.server` (see `tests/test_adapter_runner.py`). Never put keys in files, and
   before sending any data to a real vendor confirm its data policy (design 11.2); evaluation data must be non-user content.

## Test sets

A test set is a directory with `items.jsonl` (one JSON object per line; lines starting `#` are skipped) and a `DATASET.md`
recording source and licence.

Translation: `id`, `source_lang`, `target_lang`, `source`, `reference`, optional `domain`, `tags`.
ASR: `id`, `source_lang`, `audio` (path relative to the items file), `reference` (transcript), optional `domain`, `tags`.
Language codes: `am ti om en`. Items are sorted by `id` within a direction for deterministic runs.

`testsets/sample/` and `testsets/sample_asr/` are tiny hand-written **synthetic** sets so the pipeline runs end to end.
**They are NOT a benchmark**: 5 trivial items per direction, references not native-speaker reviewed, the audio is silence.

Candidate real sources (all licences and terms are TO VERIFY before use; nothing is downloaded by this repo):
- FLORES-200 (am, ti, om, en devtest/dev; MT) - believed CC BY-SA 4.0; check share-alike implications.
- NTREX-128 (MT; includes Amharic) - verify licence.
- Common Voice / FLEURS (ASR; Amharic, Oromo) - verify per-release licence (FLEURS believed CC BY 4.0, Common Voice CC0).
- Native-speaker panels commissioned for the project (section 21.4) - own the licence in the contract.

Rules: every dataset must have its licence recorded in its `DATASET.md` (and in `language_datasets` per R7) and permit the
intended use; evaluation data must never contain user content or personal data; do not commit large third-party corpora
without a licence review.

## Metrics

All text is NFC-normalised and compared by Unicode code point. Ethiopic syllables are single code points, so CER and
chrF character n-grams are per syllable (not comparable with Latin-script CER).

| Column | Definition |
|---|---|
| `chrF` | chrF2: character n-grams 1..6, whitespace removed, beta=2; mean of sentence scores, 0-100 |
| `chrF++` | as chrF plus word 1..2-grams (design 11.8 names chrF++) |
| `WER`, `CER` | Levenshtein over words / characters (whitespace removed) divided by reference length; ASR only; lower is better, can exceed 1 |
| `script_ok_rate` | share of letters in the expected script (Ethiopic U+1200-139F, U+2D80-2DDF, U+AB00-AB2F for am/ti; Latin for om/en); mirrors the design 11.4 output check (>= 90% Ethiopic) |
| `p50/p95_latency_ms` | nearest-rank percentiles of successful-request wall time |
| `n`, `n_ok`, `errors`, `timeouts` | items attempted / succeeded / failed (any cause) / failed by timeout. Quality metrics average over successes only, so read them with `n_ok` |

COMET and human ratings (adequacy, fluency, honorifics) are not computed here; they are added to `ai_evaluation_runs` separately.

## How results are used

- **G0**: run candidate providers over the real test sets; shortlist by chrF/WER, script rate, latency, error rate, plus
  licence/data-policy checks. Keep the CSV and `config_hash` with the decision record.
- **R2**: an evaluation run per advertised feature x pair, compared against the tier thresholds (set in B5.1), with native-speaker
  review on top. Automatic scores alone never promote a `quality_tier`.
