const encoder = new TextEncoder();
const hex = bytes => [...new Uint8Array(bytes)].map(b => b.toString(16).padStart(2, '0')).join('');
async function digest(value) { return new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(value))); }
function equalBytes(a, b) {
  if (a.length !== b.length) return false;
  let difference = 0;
  for (let i = 0; i < a.length; i++) difference |= a[i] ^ b[i];
  return difference === 0;
}
export async function verifyDeveloperCode(input, expected) {
  if (typeof expected !== 'string' || expected.length < 16 || typeof input !== 'string' || input.length > 256) return false;
  return equalBytes(await digest(input), await digest(expected));
}
async function signature(message, secret) {
  const key = await crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return hex(await crypto.subtle.sign('HMAC', key, encoder.encode(message)));
}
export async function issueGrant(personaId, generation, secret, now = Date.now()) {
  if (!secret || secret.length < 16) throw new Error('Developer code is not configured.');
  const payload = `${personaId}:${generation}:${now + 30 * 60 * 1000}`;
  return `${payload}:${await signature(payload, secret)}`;
}
export async function verifyGrant(token, personaId, generation, secret, now = Date.now()) {
  if (!token || !secret || secret.length < 16 || token.length > 250) return false;
  const [id, gen, expires, sig, extra] = token.split(':');
  if (extra !== undefined || id !== personaId || gen !== String(generation) || !/^\d+$/.test(expires || '') || !/^[0-9a-f]{64}$/.test(sig || '')) return false;
  const expiry = Number(expires);
  if (expiry <= now || expiry > now + 30 * 60 * 1000) return false;
  const expected = await signature(`${id}:${gen}:${expires}`, secret);
  return equalBytes(encoder.encode(expected), encoder.encode(sig));
}
