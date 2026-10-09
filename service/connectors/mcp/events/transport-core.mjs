import { callbackUrl, resolvePublic } from './network.mjs';
import { CallbackError } from './security.mjs';

// fetchImpl must provide a connection-time public-network restriction. Production
// supplies Workers global fetch with global_fetch_strictly_public enabled. The
// DNS check below rejects mixed answers early; it alone is not a rebinding guard.
export async function postPublic(rawUrl, body, headers, { fetchImpl, timeoutMs = 5000, beforeSend = async () => {} }) {
  const url = callbackUrl(rawUrl), controller = new AbortController();
  let timer, reader;
  const work = async () => {
    if (Object.entries(headers).some(([key, value]) => /[\r\n]/.test(key + value))) throw new CallbackError('invalid_headers');
    await resolvePublic(url, fetchImpl, controller.signal);
    await beforeSend();
    controller.signal.throwIfAborted();
    const response = await fetchImpl(url.href, { method: 'POST', body, headers,
      redirect: 'manual', signal: controller.signal });
    if (Number(response.headers.get('content-length')) > 262144) {
      await response.body?.cancel();
      throw new CallbackError('response_too_large');
    }
    if (!response.body) return { status: response.status, body: '' };
    reader = response.body.getReader();
    const decoder = new TextDecoder(); let text = '', length = 0;
    while (true) {
      const result = await reader.read();
      if (result.done) break;
      length += result.value.byteLength;
      if (length > 262144) throw new CallbackError('response_too_large');
      text += decoder.decode(result.value, { stream: true });
    }
    return { status: response.status, body: text + decoder.decode() };
  };
  try {
    return await Promise.race([work(), new Promise((_, reject) => {
      timer = setTimeout(() => { controller.abort(); reject(new CallbackError('timeout')); }, timeoutMs);
    })]);
  } catch (error) {
    if (error instanceof CallbackError) throw error;
    throw new CallbackError(controller.signal.aborted ? 'timeout' : 'connection_failed');
  } finally {
    clearTimeout(timer); controller.abort(); await reader?.cancel().catch(() => undefined);
  }
}
