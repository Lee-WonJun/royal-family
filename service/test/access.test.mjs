import test from 'node:test';
import assert from 'node:assert/strict';
import { issueGrant, verifyDeveloperCode, verifyGrant } from '../app/api/access-crypto.mjs';

const code = 'fixture-code-for-security-tests-only';
const now = Date.UTC(2026, 9, 9);
test('code verification never accepts an absent, weak, or incorrect code', async () => {
  assert.equal(await verifyDeveloperCode(code, code), true);
  for (const input of ['', undefined, 'wrong', code + ' ']) assert.equal(await verifyDeveloperCode(input, code), false);
  assert.equal(await verifyDeveloperCode('1234', '1234'), false);
  assert.equal(await verifyDeveloperCode('', undefined), false);
});
test('grant is bound to a persona and dataset generation', async () => {
  const token = await issueGrant('m02', 3, code, now);
  assert.equal(await verifyGrant(token, 'm02', 3, code, now), true);
  assert.equal(await verifyGrant(token, 'm03', 3, code, now), false);
  assert.equal(await verifyGrant(token, 'm02', 4, code, now), false);
});
test('expired, altered, future, and rotated grants are rejected', async () => {
  const token = await issueGrant('m02', 3, code, now);
  assert.equal(await verifyGrant(token, 'm02', 3, code, now + 1800000), false);
  assert.equal(await verifyGrant(token, 'm02', 3, code, now - 1), false);
  assert.equal(await verifyGrant(token.replace('m02', 'm03'), 'm03', 3, code, now), false);
  assert.equal(await verifyGrant(token.slice(0,-1) + (token.endsWith('f') ? 'e' : 'f'), 'm02', 3, code, now), false);
  assert.equal(await verifyGrant(token, 'm02', 3, 'another-fixture-secret-value', now), false);
});
test('fabricated unlocked values cannot act as grants', async () => {
  for (const token of ['true', '1', 'unlocked', 'm02:3:99999999999999:fake', '']) {
    assert.equal(await verifyGrant(token, 'm02', 3, code, now), false);
  }
});
