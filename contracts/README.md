# contracts

`openapi.yaml` is a synced copy of `docs/Ethiopian_AI_Keyboard_OpenAPI_v2_0.yaml`.

- **Edit the docs copy only**, then run `./contracts/sync.sh` to copy it here.
- `./contracts/sync.sh --check` exits 1 when the two differ; CI runs it (plan step F0.2).
- Clients (Android, iOS, admin-web) and backend contract tests generate from / validate against this file.
