import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { signatureHeaders, signingKey, constantEqual, seal, unseal, subscriptionId, normalizedArguments } from '../connectors/mcp/events/security.mjs';
import { publicAddress, callbackUrl, resolvePublic, parseHttpResponse } from '../connectors/mcp/events/network.mjs';
import { expiry, verifyCallback, deliveryOutcome } from '../connectors/mcp/events/protocol.mjs';
import { postPinned } from '../connectors/mcp/events/transport-core.mjs';

const secret = 'whsec_' + Buffer.alloc(32, 7).toString('base64');
const master = Buffer.alloc(32, 19).toString('base64');
test('exact UTF-8 bytes, stable event IDs and fresh signing time match Standard Webhooks', async () => {
  const body = JSON.stringify({ eventId: 'evt1', data: { summary: '자료 변화' } });
  const first = await signatureHeaders([secret], 'evt1', body, 'sub1', 1791500000000);
  const expected = createHmac('sha256', Buffer.alloc(32, 7)).update(`evt1.1791500000.${body}`).digest('base64');
  assert.equal(first['webhook-signature'], `v1,${expected}`);
  const later = await signatureHeaders([secret, 'whsec_' + Buffer.alloc(32, 8).toString('base64')], 'evt1', body, 'sub1', 1791500005000);
  assert.equal(later['webhook-id'], first['webhook-id']); assert.notEqual(later['webhook-signature'], first['webhook-signature']);
  assert.equal(later['webhook-signature'].split(' ').length, 2);
});
test('encrypted signing secrets are bound to a subscription and validation is strict', async () => {
  const encrypted = await seal(secret, master, 'sub1');
  assert.ok(!encrypted.includes(secret)); assert.equal(await unseal(encrypted, master, 'sub1'), secret);
  await assert.rejects(unseal(encrypted, master, 'sub2'));
  for (const invalid of ['short', 'whsec_%%%%', 'whsec_' + Buffer.alloc(8).toString('base64')]) assert.throws(() => signingKey(invalid));
  assert.equal(constantEqual('same', 'same'), true); assert.equal(constantEqual('same', 'same\0'), false);
});
test('subscription identity ignores object and set order, but never principal identity', async () => {
  const a = { clan_id: 'demo_a', asset_id: 'asset01', fields: ['owner_name', 'area_m2', 'owner_name'] };
  const b = { fields: ['area_m2', 'owner_name'], asset_id: 'asset01', clan_id: 'demo_a' };
  assert.deepEqual(normalizedArguments(a), normalizedArguments(b));
  assert.equal(await subscriptionId('a', 'https://receiver.example/path', 'asset.record.updated', a), await subscriptionId('a', 'https://receiver.example/path', 'asset.record.updated', b));
  assert.notEqual(await subscriptionId('b', 'https://receiver.example/path', 'asset.record.updated', a), await subscriptionId('a', 'https://receiver.example/path', 'asset.record.updated', a));
});
test('public address validation rejects private, reserved, mapped and mixed DNS answers', async () => {
  for (const ip of ['127.0.0.1', '0.0.0.0', '10.1.2.3', '100.64.0.1', '192.168.1.1', '198.19.1.1', '169.254.169.254', '192.0.2.1', '224.0.0.1', '::1', '::ffff:7f00:1', 'fd00::1', '2001:db8::1', '2002:7f00:1::1', '3fff::1']) assert.equal(publicAddress(ip), false, ip);
  for (const ip of ['1.1.1.1', '8.8.8.8', '2606:4700:4700::1111', '2001:4860:4860::8888']) assert.equal(publicAddress(ip), true, ip);
  for (const url of ['http://public.example', 'https://127.0.0.1', 'https://user:password@public.example', 'https://host.local', 'https://public.example:8443']) assert.throws(() => callbackUrl(url));
  const fakeDns = async url => Response.json({ Status: 0, Answer: url.includes('type=AAAA') ? [] : [{ type: 1, data: '8.8.8.8' }, { type: 1, data: '10.0.0.1' }] });
  await assert.rejects(resolvePublic(callbackUrl('https://receiver.example/path'), fakeDns), error => error.reason === 'non_public_address');
});
test('HTTP parsing retains body bytes and does not turn redirects into success', () => {
  const wire = new TextEncoder().encode('HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n');
  assert.deepEqual(parseHttpResponse(wire), { status: 200, body: 'hello' });
  assert.equal(parseHttpResponse(new TextEncoder().encode('HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1\r\nContent-Length: 0\r\n\r\n')).status, 302);
  assert.throws(() => parseHttpResponse(new TextEncoder().encode('HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\na')));
});
test('subscription challenge requires both receipt and an exact echo; TTL is bounded', async () => {
  const echo = async (_url, body) => ({ status: 200, body: JSON.stringify({ challenge: JSON.parse(body).challenge }) });
  const args = { url: 'https://receiver.example', secret, id: 'sub1', post: echo, now: () => 1000, randomId: () => 'unique-test-challenge' };
  assert.equal(await verifyCallback(args), new Date(1000).toISOString());
  for (const response of [{ status: 200, body: '{}' }, { status: 500, body: '{"challenge":"unique-test-challenge"}' }, { status: 200, body: '{"challenge":"wrong"}' }])
    await assert.rejects(verifyCallback({ ...args, post: async () => response }), error => error.reason === 'challenge_failed');
  for (const ttl of [undefined, null, 864000000]) assert.equal(Date.parse(expiry(ttl, 1000)), 86401000);
  assert.equal(Date.parse(expiry(5000, 1000)), 6000);
});
test('delivery stops on permanent errors and caps transient attempts at three', () => {
  for (const code of [301, 400, 401, 403, 410, 413]) assert.equal(deliveryOutcome(code, null, 1), 'failed');
  for (const code of [429, 500, 503]) {
    assert.equal(deliveryOutcome(code, null, 1), 'retry_wait'); assert.equal(deliveryOutcome(code, null, 3), 'failed');
  }
  assert.equal(deliveryOutcome(0, 'non_public_address', 1), 'failed');
  assert.equal(deliveryOutcome(0, 'timeout', 2), 'retry_wait');
  assert.equal(deliveryOutcome(204, null, 1), 'delivered');
});
test('connection uses the verified IP while TLS and Host retain the original hostname', async () => {
  const received = [], sockets = []; let tlsName, reads = 0;
  const tls = { opened: Promise.resolve(), close: async () => {},
    writable: { getWriter: () => ({ write: async bytes => received.push(new TextDecoder().decode(bytes)), releaseLock() {} }) },
    readable: { getReader: () => ({ read: async () => reads++ ? { done: true } : { done: false, value: new TextEncoder().encode('HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n') }, releaseLock() {} }) } };
  const connect = address => { sockets.push(address); return { opened: Promise.resolve(), close: async () => {}, startTls: options => { tlsName = options.expectedServerHostname; return tls; } }; };
  const fetchImpl = async url => Response.json({ Status: 0, Answer: url.includes('type=AAAA') ? [] : [{ type: 1, data: '8.8.8.8' }] });
  const response = await postPinned('https://receiver.example/hook', '{}', { 'Content-Type': 'application/json' }, { connect, fetchImpl });
  assert.equal(response.status, 204); assert.deepEqual(sockets, [{ hostname: '8.8.8.8', port: 443 }]);
  assert.equal(tlsName, 'receiver.example'); assert.ok(received.join('').includes('Host: receiver.example\r\n'));
  assert.equal(received.at(-1), '{}');
  let called = false;
  await assert.rejects(postPinned('https://receiver.example/hook', '{}', {}, { connect: () => { called = true; },
    fetchImpl: async () => Response.json({ Status: 0, Answer: [{ type: 1, data: '127.0.0.1' }] }) }));
  assert.equal(called, false, 'blocked address must never reach the socket boundary');
});
