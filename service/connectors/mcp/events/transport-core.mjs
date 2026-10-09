import { callbackUrl, resolvePublic, parseHttpResponse } from './network.mjs';
import { CallbackError } from './security.mjs';

export async function postPinned(rawUrl, body, headers, { connect, fetchImpl = fetch, timeoutMs = 5000, beforeSend = async () => {} }) {
  const url = callbackUrl(rawUrl), controller = new AbortController();
  let socket;
  let timer;
  const work = async () => {
    const address = await resolvePublic(url, fetchImpl, controller.signal);
    // Pin the checked address. TLS still verifies the callback's original hostname.
    const plain = connect({ hostname: address, port: 443 }, { secureTransport: 'starttls', allowHalfOpen: false });
    socket = plain;
    await plain.opened;
    socket = plain.startTls({ expectedServerHostname: url.hostname });
    await socket.opened;
    const content = new TextEncoder().encode(body);
    const lines = [`POST ${url.pathname}${url.search} HTTP/1.1`, `Host: ${url.hostname}`, 'Connection: close', `Content-Length: ${content.length}`,
      ...Object.entries(headers).map(([name, value]) => `${name}: ${value}`), '', ''];
    if (Object.entries(headers).some(([key, value]) => /[\r\n]/.test(key + value))) throw new CallbackError('invalid_headers');
    const writer = socket.writable.getWriter();
    await beforeSend();
    await writer.write(new TextEncoder().encode(lines.join('\r\n'))); await writer.write(content); writer.releaseLock();
    const reader = socket.readable.getReader(), chunks = [];
    let length = 0;
    while (true) {
      const result = await reader.read();
      if (result.done) break;
      length += result.value.length;
      if (length > 278528) throw new CallbackError('response_too_large');
      chunks.push(result.value);
    }
    reader.releaseLock();
    const response = new Uint8Array(length); let position = 0;
    for (const chunk of chunks) { response.set(chunk, position); position += chunk.length; }
    return parseHttpResponse(response);
  };
  try {
    return await Promise.race([work(), new Promise((_, reject) => {
      timer = setTimeout(() => { controller.abort(); reject(new CallbackError('timeout')); }, timeoutMs);
    })]);
  } catch (error) {
    if (error instanceof CallbackError) throw error;
    throw new CallbackError('connection_failed');
  } finally {
    clearTimeout(timer); controller.abort(); await socket?.close().catch(() => undefined);
  }
}
