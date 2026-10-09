import { isIP } from 'node:net';
import { CallbackError } from './security.mjs';

export function publicAddress(ip) {
  if (isIP(ip) === 4) {
    const [a, b, c] = ip.split('.').map(Number);
    return !(a === 0 || a === 10 || a === 127 || a >= 224 || a === 169 && b === 254 || a === 172 && b >= 16 && b <= 31 ||
      a === 100 && b >= 64 && b <= 127 || a === 192 && (b === 168 || b === 0 && (c === 0 || c === 2) || b === 88 && c === 99) ||
      a === 198 && (b === 18 || b === 19 || b === 51 && c === 100) || a === 203 && b === 0 && c === 113);
  }
  if (isIP(ip) !== 6 || ip.includes('.')) return false;
  const [left, right = ''] = ip.toLowerCase().split('::');
  const head = left ? left.split(':') : [], tail = right ? right.split(':') : [];
  const parts = [...head, ...Array(8 - head.length - tail.length).fill('0'), ...tail].map(value => parseInt(value, 16));
  return (parts[0] & 0xe000) === 0x2000 && parts[0] !== 0x2002 && !(parts[0] === 0x2001 && (parts[1] < 0x200 || parts[1] === 0xdb8)) &&
    !(parts[0] === 0x3fff && parts[1] < 0x1000);
}
export function callbackUrl(raw) {
  let url;
  try { url = new URL(raw); } catch { throw new CallbackError('invalid_url'); }
  if (url.protocol !== 'https:' || url.username || url.password || url.hash || url.port && url.port !== '443' ||
      isIP(url.hostname.replace(/^\[|\]$/g, '')) || !url.hostname.includes('.') || /(?:^|\.)(localhost|local|internal|test|invalid)$/.test(url.hostname)) throw new CallbackError('invalid_url');
  return url;
}
export async function resolvePublic(url, fetcher = fetch, signal) {
  const responses = await Promise.all(['A', 'AAAA'].map(async type => {
    const response = await fetcher(`https://cloudflare-dns.com/dns-query?name=${encodeURIComponent(url.hostname)}&type=${type}`, { headers: { Accept: 'application/dns-json' }, redirect: 'error', signal });
    if (!response.ok) throw new CallbackError('dns_failed');
    const data = await response.json();
    if (![0, 3].includes(data.Status)) throw new CallbackError('dns_failed');
    return (data.Answer || []).filter(answer => answer.type === 1 || answer.type === 28).map(answer => answer.data);
  }));
  const addresses = [...new Set(responses.flat())];
  if (!addresses.length) throw new CallbackError('dns_failed');
  if (addresses.some(address => !publicAddress(address))) throw new CallbackError('non_public_address');
  return addresses.find(address => isIP(address) === 4) || addresses[0];
}
export function parseHttpResponse(bytes) {
  const view = new TextDecoder('latin1').decode(bytes);
  const split = view.indexOf('\r\n\r\n');
  if (split < 0 || split > 16384) throw new CallbackError('invalid_response');
  const lines = view.slice(0, split).split('\r\n'), match = /^HTTP\/1\.[01] (\d{3})/.exec(lines.shift());
  if (!match) throw new CallbackError('invalid_response');
  const headers = new Map(lines.map(line => { const at = line.indexOf(':'); return [line.slice(0, at).toLowerCase(), line.slice(at + 1).trim()]; }));
  let body = bytes.slice(split + 4);
  if (/chunked/i.test(headers.get('transfer-encoding') || '')) {
    const chunks = []; let offset = 0, length = 0;
    while (true) {
      const end = new TextDecoder('latin1').decode(body.slice(offset)).indexOf('\r\n');
      if (end < 0) throw new CallbackError('invalid_response');
      const sizeText = new TextDecoder().decode(body.slice(offset, offset + end)).split(';')[0];
      if (!/^[0-9a-f]+$/i.test(sizeText)) throw new CallbackError('invalid_response');
      const size = parseInt(sizeText, 16); offset += end + 2;
      if (!size) break;
      if (offset + size + 2 > body.length || body[offset + size] !== 13 || body[offset + size + 1] !== 10) throw new CallbackError('invalid_response');
      length += size;
      if (length > 262144) throw new CallbackError('response_too_large');
      chunks.push(body.slice(offset, offset + size)); offset += size + 2;
    }
    body = new Uint8Array(length); let position = 0;
    for (const chunk of chunks) { body.set(chunk, position); position += chunk.length; }
  } else if (headers.has('content-length') && Number(headers.get('content-length')) !== body.length) throw new CallbackError('invalid_response');
  if (body.length > 262144) throw new CallbackError('response_too_large');
  return { status: Number(match[1]), body: new TextDecoder().decode(body) };
}
