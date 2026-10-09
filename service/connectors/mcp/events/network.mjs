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
