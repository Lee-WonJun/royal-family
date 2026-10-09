import { postPublic } from './transport-core.mjs';

export function publicPost(url: string, body: string, headers: Record<string, string>, timeoutMs = 5000, beforeSend: () => Promise<void> = async () => {}) {
  // Workers native fetch + global_fetch_strictly_public is the connection-time
  // public-network boundary. Do not replace this with unrestricted Node fetch.
  return postPublic(url, body, headers, { fetchImpl: fetch, timeoutMs, beforeSend });
}
