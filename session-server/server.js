// NC Studio reference session server (Mode 2 MVP).
//
// Responsibilities, and nothing else:
//   * sessions + short-lived invite codes + scoped bearer tokens
//   * a WebSocket relay so control/telemetry envelopes and monitor audio frames
//     reach the other phone (the app falls back to this transport by design)
//   * the take upload API from upload-api.yaml, with per-chunk and whole-take
//     SHA-256 verification
//   * a probe endpoint for preflight bandwidth measurement
//
// The audio take never depends on this server: the artist's phone records
// locally and uploads finished chunks. Killing this process mid-session loses
// nothing but the live link.

import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, readFile, readdir, rename, rm, stat, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';

const INVITE_TTL_MS = Number(process.env.INVITE_TTL_MS ?? 30 * 60 * 1000);
const TOKEN_TTL_MS = Number(process.env.TOKEN_TTL_MS ?? 12 * 60 * 60 * 1000);
const CHUNK_MAX_BYTES = Number(process.env.CHUNK_MAX_BYTES ?? 8 * 1024 * 1024);
const MAX_RELAY_HISTORY = 512;
const SAFE_TAKE_ID = /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/;
const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '::1', '[::1]']);

export function createSessionServer(options = {}) {
  const dataDir = resolve(options.dataDir ?? join(process.cwd(), 'data'));
  const sessions = new Map(); // sessionId -> session
  const invites = new Map(); // inviteCode -> sessionId

  const sockets = new Map(); // sessionId -> Map<role, ws>
  const relayed = []; // for tests: { sessionId, from, kind, bytes }

  const now = () => Date.now();

  /**
   * A browser upgrade carries an `Origin`; a native client does not. Allow
   * loopback (this machine's own tooling) plus whatever the operator listed in
   * `SESSION_ALLOWED_ORIGINS`, and reject every other browser origin. The
   * token check below still runs for every request that gets this far.
   */
  function originAllowed(origin) {
    let parsed;
    try {
      parsed = new URL(origin);
    } catch {
      return false;
    }
    if (LOOPBACK_HOSTS.has(parsed.hostname)) return true;
    const allowed = (process.env.SESSION_ALLOWED_ORIGINS ?? '')
      .split(',')
      .map((value) => value.trim())
      .filter(Boolean);
    return allowed.includes(origin);
  }

  const sha256 = (buffer) => createHash('sha256').update(buffer).digest('hex');

  function issueToken(session, role) {
    const token = randomBytes(24).toString('hex');
    session.tokens.set(token, { role, expiresAt: now() + TOKEN_TTL_MS });
    return token;
  }

  function makeInviteCode() {
    const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    for (let attempt = 0; attempt < 100; attempt += 1) {
      let code = '';
      for (let i = 0; i < 6; i += 1) {
        code += alphabet[Math.floor(Math.random() * alphabet.length)];
      }
      if (!invites.has(code)) return code;
    }
    throw new Error('could not allocate invite code');
  }

  function createSession({ role = 'engineer', displayName = null } = {}) {
    pruneExpired();
    if (role !== 'engineer') {
      const error = new Error('only the engineer creates a session; the artist joins with a code');
      error.status = 422;
      throw error;
    }
    const sessionId = randomUUID();
    const inviteCode = makeInviteCode();
    const session = {
      sessionId,
      inviteCode,
      inviteExpiresAt: now() + INVITE_TTL_MS,
      createdAt: now(),
      engineerName: displayName,
      artistName: null,
      artistJoined: false,
      lastSeen: now(),
      tokens: new Map(),
      takes: new Map(),
    };
    const token = issueToken(session, 'engineer');
    sessions.set(sessionId, session);
    invites.set(inviteCode, sessionId);
    return { session, token, role: 'engineer' };
  }

  function joinSession({ inviteCode = '', displayName = null } = {}) {
    pruneExpired();
    const sessionId = invites.get(String(inviteCode).trim().toUpperCase());
    const session = sessionId ? sessions.get(sessionId) : undefined;
    if (!session) {
      const error = new Error('unknown invite code');
      error.status = 404;
      throw error;
    }
    if (now() > session.inviteExpiresAt) {
      const error = new Error('invite code expired');
      error.status = 410;
      throw error;
    }
    // The session has exactly one artist. The invite code is not a seat that
    // can be handed to a second phone: refuse rather than silently allowing a
    // third participant to receive the monitor stream and control events.
    if (session.artistJoined) {
      const error = new Error('this session already has an artist');
      error.status = 409;
      error.code = 'SESSION_FULL';
      throw error;
    }
    session.artistJoined = true;
    session.artistName = displayName ?? session.artistName;
    const token = issueToken(session, 'artist');
    return { session, token, role: 'artist' };
  }

  /**
   * Drops sessions whose last activity is older than the token TTL, and token
   * entries that have expired. Without this the session map (and every token in
   * it) grew for the lifetime of the process.
   */
  function pruneExpired() {
    const cutoff = now() - TOKEN_TTL_MS;
    for (const [sessionId, session] of sessions) {
      for (const [token, record] of session.tokens) {
        if (now() > record.expiresAt) session.tokens.delete(token);
      }
      // A live socket keeps the session even if nobody has authenticated lately.
      if (sockets.has(sessionId)) continue;
      if ((session.lastSeen ?? session.createdAt) < cutoff) {
        sessions.delete(sessionId);
        invites.delete(session.inviteCode);
      }
    }
    for (const [code, sessionId] of invites) {
      if (!sessions.has(sessionId)) invites.delete(code);
    }
  }

  function authenticate(sessionId, token) {
    const session = sessions.get(sessionId);
    if (!session) return null;
    const record = session.tokens.get(token);
    if (!record) return null;
    if (now() > record.expiresAt) {
      session.tokens.delete(token);
      return null;
    }
    session.lastSeen = now();
    return { session, role: record.role };
  }

  function bearer(req) {
    const header = req.headers['authorization'] ?? '';
    return header.startsWith('Bearer ') ? header.slice(7) : null;
  }

  // ---------------------------------------------------------------- uploads

  function takeDir(sessionId, takeId) {
    return join(dataDir, 'takes', sessionId, takeId);
  }

  async function readJson(req) {
    const chunks = [];
    let total = 0;
    for await (const chunk of req) {
      total += chunk.length;
      if (total > 4 * 1024 * 1024) {
        const error = new Error('request too large');
        error.status = 413;
        throw error;
      }
      chunks.push(chunk);
    }
    try {
      return JSON.parse(Buffer.concat(chunks).toString('utf8'));
    } catch {
      const error = new Error('request body is not valid JSON');
      error.status = 400;
      error.code = 'INVALID_JSON';
      throw error;
    }
  }

  async function readBody(req, maxBytes) {
    const chunks = [];
    let total = 0;
    for await (const chunk of req) {
      total += chunk.length;
      if (total > maxBytes) {
        const error = new Error('chunk too large');
        error.status = 413;
        throw error;
      }
      chunks.push(chunk);
    }
    return Buffer.concat(chunks);
  }

  function validManifest(manifest) {
    if (!manifest || typeof manifest !== 'object') return false;
    // takeId is used as a directory name, so it must never contain a path
    // separator, a dot segment, or anything the filesystem treats specially.
    if (typeof manifest.takeId !== 'string' || !SAFE_TAKE_ID.test(manifest.takeId)) return false;
    if (typeof manifest.sessionId !== 'string' || manifest.sessionId.length === 0) return false;
    if (!Number.isInteger(manifest.trackId) || manifest.trackId < 0) return false;
    if (!Number.isInteger(manifest.sampleRate) || ![44100, 48000, 96000].includes(manifest.sampleRate)) return false;
    if (!Number.isInteger(manifest.bitDepth) || ![16, 24, 32].includes(manifest.bitDepth)) return false;
    if (!Number.isInteger(manifest.channels) || ![1, 2].includes(manifest.channels)) return false;
    if (!Number.isInteger(manifest.totalFrames) || manifest.totalFrames <= 0) return false;
    if (!Number.isInteger(manifest.startFrame) || manifest.startFrame < 0) return false;
    if (!Number.isInteger(manifest.calibrationOffsetFrames)) return false;
    if (!Array.isArray(manifest.chunks) || manifest.chunks.length < 1) return false;
    if (!/^[0-9a-f]{64}$/.test(manifest.takeSha256 ?? '')) return false;

    const seen = new Set();
    for (const chunk of manifest.chunks) {
      if (!chunk || typeof chunk !== 'object') return false;
      if (!Number.isInteger(chunk.index) || chunk.index < 0) return false;
      if (seen.has(chunk.index)) return false;
      seen.add(chunk.index);
      // Chunk indices define the concatenation order for the whole-take hash;
      // a gap or a duplicate would make that hash ambiguous.
      if (chunk.index !== seen.size - 1) return false;
      if (!Number.isInteger(chunk.bytes) || chunk.bytes <= 0 || chunk.bytes > CHUNK_MAX_BYTES) return false;
      if (!/^[0-9a-f]{64}$/.test(chunk.sha256 ?? '')) return false;
    }
    return true;
  }

  async function storedChunks(sessionId, takeId) {
    const dir = join(takeDir(sessionId, takeId), 'chunks');
    if (!existsSync(dir)) return new Map();
    const entries = await readdir(dir);
    const map = new Map();
    for (const name of entries) {
      const match = /^(\d+)\.part$/.exec(name);
      if (!match) continue;
      const file = join(dir, name);
      map.set(Number(match[1]), { file, bytes: (await stat(file)).size });
    }
    return map;
  }

  async function chunkHash(file) {
    const buffer = await readFile(file);
    return { hash: sha256(buffer), buffer };
  }

  function missingChunks(manifest, stored) {
    return manifest.chunks
      .filter((chunk) => !stored.has(chunk.index) || stored.get(chunk.index).bytes !== chunk.bytes)
      .map((chunk) => chunk.index);
  }

  function takeStatus(record, takeId, stored) {
    return {
      takeId,
      state: record.verified ? 'verified' : record.state,
      missingChunks: record.verified ? [] : missingChunks(record.manifest, stored),
    };
  }

  // ------------------------------------------------------------ HTTP routes

  const server = createServer(async (req, res) => {
    const url = new URL(req.url, `http://${req.headers.host ?? 'localhost'}`);
    const path = url.pathname;
    try {
      if (req.method === 'POST' && path === '/v1/sessions') {
        const body = await readJson(req).catch(() => ({}));
        const { session, token, role } = createSession(body);
        return json(res, 201, {
          sessionId: session.sessionId,
          token,
          role,
          inviteCode: session.inviteCode,
          inviteExpiresAt: session.inviteExpiresAt,
        });
      }

      if (req.method === 'POST' && path === '/v1/sessions/join') {
        const body = await readJson(req).catch(() => ({}));
        const { session, token, role } = joinSession(body);
        return json(res, 200, {
          sessionId: session.sessionId,
          token,
          role,
          peerName: session.engineerName,
        });
      }

      if (req.method === 'GET' && path.startsWith('/v1/sessions/')) {
        const sessionId = path.slice('/v1/sessions/'.length);
        const auth = authenticate(sessionId, bearer(req));
        if (!auth) return json(res, 401, { code: 'UNAUTHORIZED', message: 'bad token' });
        return json(res, 200, {
          sessionId,
          inviteCode: auth.role === 'engineer' ? auth.session.inviteCode : undefined,
          artistName: auth.session.artistName,
          engineerName: auth.session.engineerName,
          createdAtEpochMs: auth.session.createdAt,
        });
      }

      if (req.method === 'POST' && path === '/v1/probe') {
        await readBody(req, 8 * 1024 * 1024).catch(() => Buffer.alloc(0));
        res.writeHead(204).end();
        return;
      }

      if (path.startsWith('/v1/takes')) {
        const auth = authenticateSessionFromManifestRoute(req, url);
        if (!auth) return json(res, 401, { code: 'UNAUTHORIZED', message: 'bad token' });
        return await handleTakes(req, res, url, auth);
      }

      return json(res, 404, { code: 'NOT_FOUND', message: `no route for ${req.method} ${path}` });
    } catch (error) {
      const status = error.status ?? 500;
      return json(res, status, { code: error.code ?? 'ERROR', message: error.message });
    }
  });

  function authenticateSessionFromManifestRoute(req, url) {
    const token = bearer(req);
    if (!token) return null;
    const headerSession = req.headers['x-session-id'];
    if (headerSession) {
      const auth = authenticate(String(headerSession), token);
      if (auth) return auth;
    }
    // Fall back to finding the token across sessions (small server, fine).
    for (const session of sessions.values()) {
      const auth = authenticate(session.sessionId, token);
      if (auth) return auth;
    }
    return null;
  }

  async function handleTakes(req, res, url, auth) {
    const parts = url.pathname.split('/').filter(Boolean); // v1, takes, ...
    if (parts.length === 2 && req.method === 'POST') {
      const manifest = await readJson(req);
      if (!validManifest(manifest)) {
        return json(res, 422, { code: 'INVALID_MANIFEST', message: 'manifest failed validation' });
      }
      if (manifest.sessionId !== auth.session.sessionId) {
        return json(res, 403, { code: 'WRONG_SESSION', message: 'manifest is for another session' });
      }
      const registry = auth.session.takes;
      const existing = registry.get(manifest.takeId);
      if (existing) {
        const same = JSON.stringify(existing.manifest) === JSON.stringify(manifest);
        if (!same) {
          return json(res, 409, {
            code: 'MANIFEST_CONFLICT',
            message: 'takeId already registered with a different manifest',
          });
        }
        const stored = await storedChunks(auth.session.sessionId, manifest.takeId);
        return json(res, 200, takeStatus(existing, manifest.takeId, stored));
      }

      const record = { manifest, state: 'registered', verified: false };
      registry.set(manifest.takeId, record);
      await mkdir(join(takeDir(auth.session.sessionId, manifest.takeId), 'chunks'), { recursive: true });
      await writeFile(
        join(takeDir(auth.session.sessionId, manifest.takeId), 'manifest.json'),
        JSON.stringify(manifest),
      );
      const stored = await storedChunks(auth.session.sessionId, manifest.takeId);
      return json(res, 201, takeStatus(record, manifest.takeId, stored));
    }

    const takeId = parts[2];
    const record = auth.session.takes.get(takeId);
    if (!takeId || !record) {
      return json(res, 404, { code: 'UNKNOWN_TAKE', message: 'take not registered' });
    }
    const sessionId = auth.session.sessionId;

    if (parts.length === 3 && req.method === 'GET') {
      const stored = await storedChunks(sessionId, takeId);
      return json(res, 200, takeStatus(record, takeId, stored));
    }

    if (parts.length === 5 && parts[3] === 'chunks') {
      const index = Number(parts[4]);
      const chunk = record.manifest.chunks.find((item) => item.index === index);
      if (!chunk) return json(res, 404, { code: 'UNKNOWN_CHUNK', message: 'no such chunk' });
      const file = join(takeDir(sessionId, takeId), 'chunks', `${index}.part`);

      if (req.method === 'PUT') {
        const declared = req.headers['x-chunk-sha256'];
        if (!/^[0-9a-f]{64}$/.test(String(declared ?? ''))) {
          return json(res, 422, { code: 'HASH_REQUIRED', message: 'X-Chunk-Sha256 header required' });
        }
        const body = await readBody(req, CHUNK_MAX_BYTES);
        const actual = sha256(body);
        if (actual !== declared || body.length !== chunk.bytes) {
          return json(res, 422, {
            code: 'HASH_MISMATCH',
            message: `chunk rejected: declared ${declared}, got ${actual} (${body.length} bytes)`,
          });
        }
        await mkdir(join(takeDir(sessionId, takeId), 'chunks'), { recursive: true });
        // A per-attempt temp name keeps two concurrent retries of the same
        // chunk from overwriting each other's in-flight bytes.
        const tmp = `${file}.${randomUUID()}.upload`;
        await writeFile(tmp, body);
        try {
          await rename(tmp, file);
        } catch (err) {
          // On Windows rename() refuses to replace an existing target, so a
          // resumed/retried upload failed here even though the bytes were fine.
          if (err && (err.code === 'EEXIST' || err.code === 'EPERM')) {
            await rm(file, { force: true });
            await rename(tmp, file);
          } else {
            await rm(tmp, { force: true });
            throw err;
          }
        }
        return res.writeHead(204).end();
      }

      if (req.method === 'GET') {
        if (!existsSync(file)) {
          return json(res, 404, { code: 'CHUNK_NOT_UPLOADED', message: 'chunk not uploaded yet' });
        }
        const buffer = await readFile(file);
        res.writeHead(200, {
          'Content-Type': 'application/octet-stream',
          'Content-Length': buffer.length,
        });
        return res.end(buffer);
      }
    }

    if (parts.length === 4 && parts[3] === 'complete' && req.method === 'POST') {
      const stored = await storedChunks(sessionId, takeId);
      const missing = missingChunks(record.manifest, stored);
      if (missing.length > 0) {
        return json(res, 409, {
          takeId,
          state: 'uploading',
          missingChunks: missing,
        });
      }
      const digest = createHash('sha256');
      for (const chunk of [...record.manifest.chunks].sort((a, b) => a.index - b.index)) {
        const { buffer, hash } = await chunkHash(stored.get(chunk.index).file);
        if (hash !== chunk.sha256) {
          return json(res, 422, {
            code: 'HASH_MISMATCH',
            message: `stored chunk ${chunk.index} does not match its manifest hash`,
          });
        }
        digest.update(buffer);
      }
      const whole = digest.digest('hex');
      if (whole !== record.manifest.takeSha256) {
        return json(res, 422, {
          code: 'TAKE_HASH_MISMATCH',
          message: 'whole-take hash does not match the manifest',
        });
      }
      record.verified = true;
      record.state = 'verified';
      return json(res, 200, { takeId, state: 'verified', missingChunks: [] });
    }

    return json(res, 405, { code: 'METHOD_NOT_ALLOWED', message: 'unsupported takes route' });
  }

  function json(res, status, payload) {
    const body = JSON.stringify(payload);
    res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
    res.end(body);
  }

  // ------------------------------------------------------------- WebSocket

  const wss = new WebSocketServer({
    noServer: true,
    // Control envelopes are small and monitor audio is capped at 64 KiB per
    // frame by the app; anything larger is not part of this protocol.
    maxPayload: 256 * 1024,
  });

  server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url, `http://${req.headers.host ?? 'localhost'}`);
    if (url.pathname !== '/ws') {
      socket.destroy();
      return;
    }
    // WebSockets do not enforce CORS, so `Origin` is the only place a browser's
    // cross-origin attempt can be stopped. A native client (the phones) sends
    // no Origin and falls through to the token check; a same-host page such as
    // a local dev tool is allowed; anything else needs an explicit opt-in.
    const origin = req.headers.origin;
    if (origin && !originAllowed(origin)) {
      socket.write('HTTP/1.1 403 Forbidden\r\n\r\n');
      socket.destroy();
      return;
    }
    const sessionId = url.searchParams.get('session');
    // Prefer the Authorization header: a token in the query string lands in
    // proxy logs, connection traces and any intermediate. The query fallback
    // remains for older clients.
    const headerToken = bearer(req);
    const token = headerToken ?? url.searchParams.get('token');
    const role = url.searchParams.get('role');
    const auth = authenticate(sessionId, token);
    if (!auth || (role && role !== auth.role)) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      wss.emit('connection', ws, req, { sessionId, role: auth.role });
    });
  });

  wss.on('connection', (ws, _req, context) => {
    const { sessionId, role } = context;
    if (!sockets.has(sessionId)) sockets.set(sessionId, new Map());
    const room = sockets.get(sessionId);
    if (room.has(role)) {
      // One connection per role: the newest wins, the old one is told why.
      room.get(role).close(4001, 'replaced by a newer connection');
    }
    room.set(role, ws);

    ws.on('message', (data, isBinary) => {
      const payload = Buffer.isBuffer(data) ? data : Buffer.from(data);
      // Diagnostic history only. A long session relays thousands of monitor
      // frames, so keep a bounded tail instead of growing without limit.
      relayed.push({ sessionId, from: role, kind: isBinary ? 'binary' : 'text', bytes: payload.length });
      if (relayed.length > MAX_RELAY_HISTORY) relayed.shift();
      for (const [peerRole, peer] of room.entries()) {
        if (peerRole === role) continue;
        if (peer.readyState === peer.OPEN) peer.send(payload, { binary: isBinary });
      }
    });

    ws.on('close', () => {
      if (room.get(role) === ws) room.delete(role);
      if (room.size === 0) sockets.delete(sessionId);
    });

    ws.on('error', () => ws.close(1011, 'socket error'));
  });

  return {
    server,
    listen(port = 0, host = '0.0.0.0') {
      return new Promise((resolvePromise) => {
        server.listen(port, host, () => resolvePromise(server.address().port));
      });
    },
    close() {
      for (const room of sockets.values()) {
        for (const ws of room.values()) ws.close(1001, 'server stopping');
      }
      sockets.clear();
      return new Promise((resolvePromise) => wss.close(() => server.close(() => resolvePromise())));
    },
    // Test hooks
    relayed,
    sessions,
    invites,
    dataDir,
  };
}

const startedDirectly = Boolean(process.argv[1]) && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (startedDirectly || process.env.START_SERVER === '1') {
  const configured = Number(process.env.PORT);
  const port = Number.isFinite(configured) && configured > 0 ? configured : 8787;
  // The two-phone session needs a LAN reachability, so loopback is not the
  // default — but the bind is a choice, not an accident, and it is announced.
  const host = process.env.SESSION_HOST?.trim() || '0.0.0.0';
  const instance = createSessionServer({ dataDir: process.env.DATA_DIR });
  instance.listen(port, host).then((bound) => {
    // eslint-disable-next-line no-console
    console.log(`NC Studio session server listening on http://${host}:${bound}`);
    console.log(`  upload API:  http://<host>:${bound}/v1/takes`);
    console.log(`  websocket:   ws://<host>:${bound}/ws`);
    console.log(`  data dir:    ${instance.dataDir}`);
    if (!LOOPBACK_HOSTS.has(host)) {
      console.warn('');
      console.warn('  WARNING: bound beyond loopback. Invite codes and bearer tokens');
      console.warn('  travel in cleartext (http://, ws://) to anyone who can reach this');
      console.warn('  address. Set SESSION_HOST=127.0.0.1 for a single-device setup, or');
      console.warn('  keep this server on a trusted LAN.');
    }
  });
}
