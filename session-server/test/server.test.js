import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { WebSocket } from 'ws';
import { createSessionServer } from '../server.js';

const sha256 = (buffer) => createHash('sha256').update(buffer).digest('hex');

async function startServer(t) {
  const dataDir = await mkdtemp(join(tmpdir(), 'nc-studio-session-'));
  const instance = createSessionServer({ dataDir });
  const port = await instance.listen(0, '127.0.0.1');
  t.after(async () => {
    await instance.close();
  });
  return { instance, baseUrl: `http://127.0.0.1:${port}`, wsUrl: `ws://127.0.0.1:${port}/ws` };
}

async function createAndJoin(baseUrl) {
  const created = await fetch(`${baseUrl}/v1/sessions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role: 'engineer', displayName: 'Engineer' }),
  });
  assert.equal(created.status, 201);
  const engineer = await created.json();

  const joined = await fetch(`${baseUrl}/v1/sessions/join`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ inviteCode: engineer.inviteCode, displayName: 'Artist' }),
  });
  assert.equal(joined.status, 200);
  const artist = await joined.json();
  return { engineer, artist };
}

function makeTake(sessionId, chunkBuffers, { takeId = randomUUID(), corruptWholeHash = false } = {}) {
  const chunks = chunkBuffers.map((buffer, index) => ({
    index,
    bytes: buffer.length,
    sha256: sha256(buffer),
  }));
  const whole = sha256(Buffer.concat(chunkBuffers));
  return {
    chunks,
    manifest: {
      takeId,
      sessionId,
      trackId: 1,
      sampleRate: 48000,
      bitDepth: 24,
      channels: 1,
      totalFrames: 480000,
      startFrame: 0,
      calibrationOffsetFrames: 1920,
      chunks,
      takeSha256: corruptWholeHash ? 'f'.repeat(64) : whole,
    },
  };
}

test('engineer creates a session and an artist joins with the invite code', async (t) => {
  const { baseUrl } = await startServer(t);
  const { engineer, artist } = await createAndJoin(baseUrl);

  assert.equal(engineer.role, 'engineer');
  assert.equal(artist.role, 'artist');
  assert.equal(artist.sessionId, engineer.sessionId);
  assert.match(engineer.inviteCode, /^[A-Z0-9]{6}$/);

  const wrongCode = await fetch(`${baseUrl}/v1/sessions/join`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ inviteCode: 'NOPE00' }),
  });
  assert.equal(wrongCode.status, 404);

  const artistCreate = await fetch(`${baseUrl}/v1/sessions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role: 'artist' }),
  });
  assert.equal(artistCreate.status, 422);
});

test('a session accepts exactly one artist', async (t) => {
  const { baseUrl } = await startServer(t);
  const { engineer } = await createAndJoin(baseUrl);

  const second = await fetch(`${baseUrl}/v1/sessions/join`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ inviteCode: engineer.inviteCode, displayName: 'Impostor' }),
  });
  assert.equal(second.status, 409);
  assert.equal((await second.json()).code, 'SESSION_FULL');
});

test('session info requires a scoped token', async (t) => {
  const { baseUrl } = await startServer(t);
  const { engineer } = await createAndJoin(baseUrl);

  const ok = await fetch(`${baseUrl}/v1/sessions/${engineer.sessionId}`, {
    headers: { Authorization: `Bearer ${engineer.token}` },
  });
  assert.equal(ok.status, 200);

  const denied = await fetch(`${baseUrl}/v1/sessions/${engineer.sessionId}`, {
    headers: { Authorization: 'Bearer nonsense' },
  });
  assert.equal(denied.status, 401);
});

test('websocket relay carries text and binary frames between the two phones', async (t) => {
  const { baseUrl, wsUrl } = await startServer(t);
  const { engineer, artist } = await createAndJoin(baseUrl);

  const artistSocket = new WebSocket(
    `${wsUrl}?session=${engineer.sessionId}&role=artist&token=${artist.token}`,
  );
  const engineerSocket = new WebSocket(
    `${wsUrl}?session=${engineer.sessionId}&role=engineer&token=${engineer.token}`,
  );
  t.after(() => {
    artistSocket.close();
    engineerSocket.close();
  });

  await Promise.all([once(artistSocket, 'open'), once(engineerSocket, 'open')]);

  const text = JSON.stringify({ v: 1, sid: engineer.sessionId, seq: 1, type: 'Arm', body: { armed: true } });
  engineerSocket.send(text);
  const [textPayload] = await once(artistSocket, 'message');
  assert.equal(textPayload.toString(), text);

  const binary = Buffer.from([1, 0, 0, 0, 7, 1, 2, 3]);
  artistSocket.send(binary);
  const [binaryPayload] = await once(engineerSocket, 'message');
  assert.deepEqual(Buffer.from(binaryPayload), binary);
});

test('websocket rejects a token that does not match the session', async (t) => {
  const { baseUrl, wsUrl } = await startServer(t);
  const { engineer } = await createAndJoin(baseUrl);

  const socket = new WebSocket(`${wsUrl}?session=${engineer.sessionId}&role=artist&token=bad`);
  const [error] = await once(socket, 'error');
  assert.match(String(error.message), /401/);
});

test('websocket refuses a cross-origin browser upgrade', async (t) => {
  const { baseUrl, wsUrl } = await startServer(t);
  const { engineer } = await createAndJoin(baseUrl);

  // Valid credentials, hostile page: WebSockets skip CORS, so Origin is the
  // only signal available and it has to be checked before the upgrade.
  const socket = new WebSocket(
    `${wsUrl}?session=${engineer.sessionId}&role=artist&token=${engineer.token}`,
    { origin: 'https://evil.example' }
  );
  const [error] = await once(socket, 'error');
  assert.match(String(error.message), /403/);
});

test('websocket still accepts a loopback origin and no origin at all', async (t) => {
  const { baseUrl, wsUrl } = await startServer(t);
  const { engineer, artist } = await createAndJoin(baseUrl);

  const browser = new WebSocket(
    `${wsUrl}?session=${engineer.sessionId}&role=engineer&token=${engineer.token}`,
    { origin: 'http://localhost:5173' }
  );
  await once(browser, 'open');
  browser.close();

  // Native clients (the phones) send no Origin header.
  const native = new WebSocket(
    `${wsUrl}?session=${engineer.sessionId}&role=artist&token=${artist.token}`
  );
  await once(native, 'open');
  native.close();
});

test('upload flow: register, upload missing chunks, verify the whole take', async (t) => {
  const { baseUrl } = await startServer(t);
  const { engineer } = await createAndJoin(baseUrl);
  const chunkData = [Buffer.from('aaaa'), Buffer.from('bbbbbb'), Buffer.from('cc')];
  const { manifest } = makeTake(engineer.sessionId, chunkData);

  const register = await fetch(`${baseUrl}/v1/takes`, {
    method: 'POST',
    headers: authHeaders(engineer),
    body: JSON.stringify(manifest),
  });
  assert.equal(register.status, 201);
  const registered = await register.json();
  assert.deepEqual(registered.missingChunks, [0, 1, 2]);

  // Re-registering the same manifest is idempotent.
  const again = await fetch(`${baseUrl}/v1/takes`, {
    method: 'POST',
    headers: authHeaders(engineer),
    body: JSON.stringify(manifest),
  });
  assert.equal(again.status, 200);

  // A different manifest for the same takeId is a conflict.
  const conflicting = await fetch(`${baseUrl}/v1/takes`, {
    method: 'POST',
    headers: authHeaders(engineer),
    body: JSON.stringify({ ...manifest, totalFrames: 1 }),
  });
  assert.equal(conflicting.status, 409);

  // Upload chunks 0 and 2, then check resume state.
  for (const index of [0, 2]) {
    const response = await putChunk(baseUrl, engineer, manifest, index, chunkData[index]);
    assert.equal(response.status, 204);
  }
  const status = await (await fetch(`${baseUrl}/v1/takes/${manifest.takeId}`, { headers: authHeaders(engineer) })).json();
  assert.deepEqual(status.missingChunks, [1]);

  // Completing early reports what is still missing.
  const early = await fetch(`${baseUrl}/v1/takes/${manifest.takeId}/complete`, {
    method: 'POST',
    headers: authHeaders(engineer),
  });
  assert.equal(early.status, 409);
  assert.deepEqual((await early.json()).missingChunks, [1]);

  const finalChunk = await putChunk(baseUrl, engineer, manifest, 1, chunkData[1]);
  assert.equal(finalChunk.status, 204);

  const complete = await fetch(`${baseUrl}/v1/takes/${manifest.takeId}/complete`, {
    method: 'POST',
    headers: authHeaders(engineer),
  });
  assert.equal(complete.status, 200);
  assert.equal((await complete.json()).state, 'verified');

  // The engineer can download each chunk and compare hashes.
  const downloaded = await fetch(`${baseUrl}/v1/takes/${manifest.takeId}/chunks/1`, {
    headers: authHeaders(engineer),
  });
  assert.equal(sha256(Buffer.from(await downloaded.arrayBuffer())), manifest.chunks[1].sha256);
});

test('manifests are rejected before a take id can reach the filesystem', async (t) => {
  const { baseUrl } = await startServer(t);
  const { engineer } = await createAndJoin(baseUrl);
  const chunkData = [Buffer.from('aaaa')];

  const traversal = makeTake(engineer.sessionId, chunkData, { takeId: '../../escape' });
  const unsafe = await fetch(`${baseUrl}/v1/takes`, {
    method: 'POST',
    headers: authHeaders(engineer),
    body: JSON.stringify(traversal.manifest),
  });
  assert.equal(unsafe.status, 422);
  assert.equal((await unsafe.json()).code, 'INVALID_MANIFEST');

  const gapped = makeTake(engineer.sessionId, [Buffer.from('aaaa'), Buffer.from('bbbb')]);
  gapped.manifest.chunks[1].index = 3;
  const gap = await fetch(`${baseUrl}/v1/takes`, {
    method: 'POST',
    headers: authHeaders(engineer),
    body: JSON.stringify(gapped.manifest),
  });
  assert.equal(gap.status, 422);

  const badJson = await fetch(`${baseUrl}/v1/takes`, {
    method: 'POST',
    headers: authHeaders(engineer),
    body: '{not json',
  });
  assert.equal(badJson.status, 400);
  assert.equal((await badJson.json()).code, 'INVALID_JSON');
});

test('upload rejects tampered chunks and whole-take hash mismatches', async (t) => {
  const { baseUrl } = await startServer(t);
  const { engineer } = await createAndJoin(baseUrl);
  const chunkData = [Buffer.from('aaaa'), Buffer.from('bbbbbb')];

  const { manifest } = makeTake(engineer.sessionId, chunkData);
  await fetch(`${baseUrl}/v1/takes`, {
    method: 'POST',
    headers: authHeaders(engineer),
    body: JSON.stringify(manifest),
  });

  const tampered = await fetch(`${baseUrl}/v1/takes/${manifest.takeId}/chunks/0`, {
    method: 'PUT',
    headers: { ...authHeaders(engineer), 'X-Chunk-Sha256': manifest.chunks[0].sha256 },
    body: Buffer.from('zzzz'),
  });
  assert.equal(tampered.status, 422);

  const noHeader = await fetch(`${baseUrl}/v1/takes/${manifest.takeId}/chunks/0`, {
    method: 'PUT',
    headers: authHeaders(engineer),
    body: chunkData[0],
  });
  assert.equal(noHeader.status, 422);

  const { manifest: corrupt } = makeTake(engineer.sessionId, chunkData, { corruptWholeHash: true });
  await fetch(`${baseUrl}/v1/takes`, {
    method: 'POST',
    headers: authHeaders(engineer),
    body: JSON.stringify(corrupt),
  });
  for (let index = 0; index < chunkData.length; index += 1) {
    await putChunk(baseUrl, engineer, corrupt, index, chunkData[index]);
  }
  const mismatch = await fetch(`${baseUrl}/v1/takes/${corrupt.takeId}/complete`, {
    method: 'POST',
    headers: authHeaders(engineer),
  });
  assert.equal(mismatch.status, 422);
  assert.equal((await mismatch.json()).code, 'TAKE_HASH_MISMATCH');
});

test('takes are scoped to the session that registered them', async (t) => {
  const { baseUrl } = await startServer(t);
  const { engineer } = await createAndJoin(baseUrl);
  const other = await (await fetch(`${baseUrl}/v1/sessions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role: 'engineer', displayName: 'Other' }),
  })).json();

  const { manifest } = makeTake(engineer.sessionId, [Buffer.from('aaaa')]);
  // Token belongs to another session than the manifest claims.
  const response = await fetch(`${baseUrl}/v1/takes`, {
    method: 'POST',
    headers: authHeaders(other),
    body: JSON.stringify(manifest),
  });
  assert.equal(response.status, 403);
});

test('probe accepts throwaway bytes for the preflight speed test', async (t) => {
  const { baseUrl } = await startServer(t);
  const response = await fetch(`${baseUrl}/v1/probe`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/octet-stream' },
    body: Buffer.alloc(256 * 1024),
  });
  assert.equal(response.status, 204);
});

function authHeaders(credentials) {
  return {
    Authorization: `Bearer ${credentials.token}`,
    'Content-Type': 'application/json',
  };
}

function putChunk(baseUrl, credentials, manifest, index, buffer) {
  return fetch(`${baseUrl}/v1/takes/${manifest.takeId}/chunks/${index}`, {
    method: 'PUT',
    headers: {
      ...authHeaders(credentials),
      'Content-Type': 'application/octet-stream',
      'X-Chunk-Sha256': manifest.chunks[index].sha256,
    },
    body: buffer,
  });
}

function once(emitter, event) {
  return new Promise((resolvePromise) => {
    emitter.once(event, (...args) => resolvePromise(args));
  });
}
