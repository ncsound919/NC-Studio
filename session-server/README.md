# NC Studio session server (reference backend)

Minimal Node backend for Mode 2 remote sessions. It exists so two phones on the
same Wi-Fi can run a session today without a cloud account. It is a reference
implementation, not hardened infrastructure.

## What it does

| Concern | Endpoint |
|---|---|
| Engineer creates a session | `POST /v1/sessions` |
| Artist joins with the invite code | `POST /v1/sessions/join` |
| Session info | `GET /v1/sessions/{sessionId}` |
| Preflight upload speed probe | `POST /v1/probe` |
| Register a take | `POST /v1/takes` |
| Upload one chunk | `PUT /v1/takes/{takeId}/chunks/{index}` + `X-Chunk-Sha256` |
| Upload status (resume truth) | `GET /v1/takes/{takeId}` |
| Verify the whole take | `POST /v1/takes/{takeId}/complete` |
| Engineer downloads a chunk | `GET /v1/takes/{takeId}/chunks/{index}` |
| Control / telemetry / monitor audio | `WS /ws?session=&role=&token=` |

Security model: bearer tokens scoped to one session, role-checked on the
WebSocket upgrade, takes scoped to the session that registered them, invite
codes expiring after 30 minutes (`INVITE_TTL_MS`). The whole-take hash is
recomputed server-side from the stored chunks before a take is called verified.

## Run it

```bash
cd session-server
npm install
npm start                # listens on 0.0.0.0:8787, data in ./data
```

Environment knobs:

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8787` | listen port |
| `DATA_DIR` | `./data` | where take chunks and manifests are stored |
| `INVITE_TTL_MS` | 30 min | invite lifetime |
| `TOKEN_TTL_MS` | 12 h | session token lifetime |
| `CHUNK_MAX_BYTES` | 8 MiB | rejected above this |

Find the LAN address the phones will use (`ipconfig` on Windows, `ip addr` on
Linux) and point both apps at `host:8787` — for example `192.168.1.20:8787`.
Windows Firewall may prompt on first run; allow private networks.

## Tests

```bash
npm test
```

Covers session create/join, token scoping, WebSocket relay (text and binary),
tamper rejection per chunk, idempotent re-registration, manifest conflicts,
resume state, and whole-take verification.

## Deliberate limitations

* In-memory sessions: restarting the server ends live sessions. Uploaded takes
  stay on disk under `data/takes/`.
* No TLS: intended for a trusted LAN during a shoot. Put it behind a TLS proxy
  before using it over the internet.
* No TURN: the MVP monitor stream and control channel run over the WebSocket, so
  both phones only need to reach this server. The WebRTC upgrade later will need
  a TURN relay.
* No retention policy: nothing is deleted automatically so no unverified take is
  ever lost; deleting `data/takes/...` is a manual decision.
