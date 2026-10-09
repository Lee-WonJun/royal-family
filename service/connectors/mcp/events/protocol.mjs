import { CallbackError, constantEqual, signatureHeaders } from './security.mjs';

export const expiry = (ttlMs, now = Date.now()) => new Date(now + Math.min(ttlMs ?? 86400000, 86400000)).toISOString();
export function deliveryOutcome(status, reason, attempts) {
  if (status >= 200 && status < 300) return 'delivered';
  const transient = status === 429 || status >= 500 || !status && ['timeout', 'connection_failed', 'dns_failed'].includes(reason || '');
  return transient && attempts < 3 ? 'retry_wait' : 'failed';
}
export async function verifyCallback({ url, secret, id, post, now = Date.now, randomId = crypto.randomUUID.bind(crypto) }) {
  const challenge = randomId(), body = JSON.stringify({ type: 'verification', challenge });
  const headers = await signatureHeaders([secret], `msg_verification_${randomId()}`, body, id, now());
  const response = await post(url, body, headers);
  let echoed;
  try { echoed = JSON.parse(response.body).challenge; } catch { throw new CallbackError('challenge_failed'); }
  if (response.status < 200 || response.status >= 300 || typeof echoed !== 'string' || !constantEqual(challenge, echoed)) throw new CallbackError('challenge_failed');
  return new Date(now()).toISOString();
}
