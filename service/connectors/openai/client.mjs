// HTTP boundary only. Domain state, authorization and mode selection live above it.
export class AiProviderError extends Error {
  constructor(code, message, status = 502, requestId = null) {
    super(message); this.code = code; this.status = status; this.requestId = requestId;
  }
}

export function createOpenAI({ apiKey, forceMock = false, fetchImpl = fetch, now = Date.now, timeoutMs = 90000, jobId, beforeRequest = async (_method) => {} }) {
  const calls = [];
  async function request(path, { method = 'POST', body, form, timeout = timeoutMs } = {}) {
    if (forceMock) throw new AiProviderError('external_disabled', '일반 테스트에서는 실제 API를 호출할 수 없습니다.', 403);
    if (!apiKey) throw new AiProviderError('not_configured', 'OpenAI 연결 설정이 필요합니다.', 503);
    await beforeRequest(method);
    if (!/^\/(responses|decisions|audio\/transcriptions|files|vector_stores)(\/[^?#]*)?$/.test(path)) {
      throw new AiProviderError('invalid_endpoint', '허용되지 않은 API입니다.', 400);
    }
    const started = now();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    let requestId = null;
    try {
      const response = await fetchImpl(`https://api.openai.com/v1${path}`, {
        method, signal: controller.signal,
        headers: { Authorization: `Bearer ${apiKey}`, ...(form ? {} : { 'Content-Type': 'application/json' }),
          ...(jobId ? { 'X-Client-Request-Id': jobId } : {}) },
        ...(body !== undefined ? { body: JSON.stringify(body) } : form ? { body: form } : {}),
      });
      requestId = response.headers.get('x-request-id');
      const data = await response.json().catch(() => { throw new AiProviderError('invalid_response', 'OpenAI 응답 형식을 확인하지 못했습니다.', 502, requestId); });
      calls.push({ endpoint: path.split('/').slice(0, 3).join('/'), request_id: requestId,
        response_id: data.id || null, model: data.model || body?.model || form?.get('model') || null,
        duration_ms: now() - started, usage: data.usage || null, status: response.ok ? 'completed' : 'failed' });
      if (!response.ok) {
        const code = data.error?.code || 'provider_error';
        const message = response.status === 401 ? 'OpenAI 인증을 확인해 주세요.'
          : code === 'insufficient_quota' ? 'OpenAI 사용 한도를 확인해 주세요.'
          : response.status === 429 ? 'OpenAI 요청이 많습니다. 잠시 후 다시 시도해 주세요.'
          : response.status === 403 || code === 'model_not_found' ? '선택한 OpenAI 모델의 접근 권한을 확인해 주세요.'
          : 'OpenAI가 요청을 처리하지 못했습니다. 호출 기록을 확인해 주세요.';
        throw new AiProviderError(code, message, response.status, requestId);
      }
      return data;
    } catch (error) {
      if (error instanceof AiProviderError) throw error;
      calls.push({ endpoint: path.split('/').slice(0, 3).join('/'), request_id: requestId, response_id: null,
        model: body?.model || form?.get('model') || null, duration_ms: now() - started, usage: null,
        status: 'failed', error: controller.signal.aborted ? 'timeout' : 'network' });
      throw new AiProviderError(controller.signal.aborted ? 'timeout' : 'network',
        'OpenAI 처리 결과를 확인하지 못했습니다. 요청이 처리됐을 수 있어 자동 재호출하지 않습니다.', 504, requestId);
    } finally { clearTimeout(timer); }
  }
  return { request, calls, now, cleanupPending: [] };
}

export function outputText(response) {
  if (response.status !== 'completed') throw new AiProviderError('incomplete', 'AI 응답이 완료되지 않았습니다. 입력 범위를 줄여 다시 실행해 주세요.');
  const content = (response.output || []).filter(x => x.type === 'message').flatMap(x => x.content || []);
  if (content.some(x => x.type === 'refusal')) throw new AiProviderError('refused', 'AI가 이 요청에 응답하지 못했습니다. 입력 자료와 요청을 확인해 주세요.');
  const text = content.filter(x => x.type === 'output_text').map(x => x.text).join('\n');
  if (!text.trim()) throw new AiProviderError('empty_response', 'AI 응답에 내용이 없습니다.');
  return text;
}
