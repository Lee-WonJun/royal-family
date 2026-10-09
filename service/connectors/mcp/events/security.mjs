export class CallbackError extends Error {
  constructor(reason) { super('Callback verification or delivery failed.'); this.reason = reason; }
}
const encoder = new TextEncoder();
export const bytesToBase64 = bytes => btoa(String.fromCharCode(...bytes));
const fromBase64 = value => Uint8Array.from(atob(value), character => character.charCodeAt(0));
export function signingKey(secret) {
  if (typeof secret !== 'string' || !/^whsec_[A-Za-z0-9+/]+={0,2}$/.test(secret)) throw new CallbackError('invalid_secret');
  let bytes;
  try { bytes = fromBase64(secret.slice(6)); } catch { throw new CallbackError('invalid_secret'); }
  if (bytes.length < 24 || bytes.length > 64) throw new CallbackError('invalid_secret');
  return bytes;
}
export function constantEqual(left, right) {
  const a = encoder.encode(String(left)), b = encoder.encode(String(right));
  let difference = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) difference |= (a[i] || 0) ^ (b[i] || 0);
  return difference === 0;
}
export async function signatureHeaders(secrets, id, body, subscriptionId, now = Date.now()) {
  const timestamp = String(Math.floor(now / 1000));
  const signatures = [];
  for (const secret of secrets) {
    const key = await crypto.subtle.importKey('raw', signingKey(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
    signatures.push(`v1,${bytesToBase64(new Uint8Array(await crypto.subtle.sign('HMAC', key, encoder.encode(`${id}.${timestamp}.${body}`))))}`);
  }
  return { 'Content-Type': 'application/json', 'webhook-id': id, 'webhook-timestamp': timestamp,
    'webhook-signature': signatures.join(' '), 'X-MCP-Subscription-Id': subscriptionId };
}
async function masterKey(master, usage) {
  let bytes;
  try { bytes = fromBase64(master); } catch { throw new CallbackError('encryption_unavailable'); }
  if (bytes.length !== 32) throw new CallbackError('encryption_unavailable');
  return crypto.subtle.importKey('raw', bytes, 'AES-GCM', false, [usage]);
}
export async function seal(secret, master, id) {
  signingKey(secret);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encrypted = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: encoder.encode(id) }, await masterKey(master, 'encrypt'), encoder.encode(secret));
  return `${bytesToBase64(iv)}.${bytesToBase64(new Uint8Array(encrypted))}`;
}
export async function unseal(value, master, id) {
  try {
    const [iv, encrypted] = value.split('.');
    const bytes = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: fromBase64(iv), additionalData: encoder.encode(id) }, await masterKey(master, 'decrypt'), fromBase64(encrypted));
    return new TextDecoder().decode(bytes);
  } catch { throw new CallbackError('encryption_unavailable'); }
}
export const fields = ['area_m2', 'land_category', 'owner_name', 'owner_type'];
export function normalizedArguments(input) {
  if (!input || Object.keys(input).some(key => !['clan_id', 'asset_id', 'fields'].includes(key)) || typeof input.clan_id !== 'string' || typeof input.asset_id !== 'string') throw new CallbackError('invalid_arguments');
  const selected = input.fields ?? fields;
  if (!Array.isArray(selected) || !selected.length || selected.some(field => !fields.includes(field))) throw new CallbackError('invalid_arguments');
  return { asset_id: input.asset_id, clan_id: input.clan_id, fields: [...new Set(selected)].sort() };
}
export function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
  return JSON.stringify(value);
}
export async function subscriptionId(principal, url, name, arguments_) {
  const bytes = await crypto.subtle.digest('SHA-256', encoder.encode(canonical([principal, url, name, normalizedArguments(arguments_)])));
  return `sub_${[...new Uint8Array(bytes)].map(byte => byte.toString(16).padStart(2, '0')).join('')}`;
}
