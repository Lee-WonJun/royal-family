import { connect } from 'cloudflare:sockets';
import { postPinned } from './transport-core.mjs';

export function pinnedPost(url: string, body: string, headers: Record<string, string>, timeoutMs = 5000, beforeSend: () => Promise<void> = async () => {}) {
  return postPinned(url, body, headers, { connect, timeoutMs, beforeSend });
}
