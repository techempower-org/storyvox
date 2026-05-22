# InstantDB wire-format fixtures

Recorded request/response shapes for the InstantDB admin HTTP API
(`POST /admin/query`, `POST /admin/transact`). Used by
`WireContractTest` to assert that `HttpInstantBackend` produces
requests in the exact shape the server expects, and parses responses
in the exact shape the server actually returns.

If InstantDB changes its wire format (envelope rename, key casing flip,
new required header, etc.), the contract test breaks loudly here
instead of breaking silently in production. That's the whole point of
this directory — see issue #714.

## What's where

| File | What it is |
|---|---|
| `query-request-blobs.json` | InstaQL query body for fetching a single `blobs` row by id |
| `query-response-blobs-hit.json` | 200 OK response when the row exists |
| `query-response-blobs-miss.json` | 200 OK response when the row does not exist (empty array) |
| `query-response-blobs-no-entity-key.json` | 200 OK response when the entity key is missing entirely |
| `query-response-error-403.json` | 4xx error envelope (`{"message": "..."}`) |
| `transact-request-update.json` | Transact body for upserting a `blobs` row |
| `transact-response-ok.json` | 200 OK response after a successful transact |
| `transact-response-error-400.json` | 4xx error envelope on validation failure |

## Source of truth

Shapes were derived from:
- `HttpInstantBackend.kt` kdoc (which itself was extracted from
  `client/packages/admin/src/index.ts` in the `instantdb/instant`
  repo).
- The post-mortem on issue #691, which is the regression these
  fixtures exist to prevent.

If a fixture diverges from what the live server returns, the right
fix is to update the fixture *and* the parser together, in the same
PR — not to silently coerce one to the other.
