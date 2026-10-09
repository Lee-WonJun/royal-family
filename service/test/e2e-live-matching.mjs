import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';

if (process.env.RF_LIVE_MATCHING !== '1') throw new Error('Enable only for the selected final live matching case.');
const base = 'http://127.0.0.1:5181', cookies = new Map(); let state, enabled = false;
async function request(path, body) {
  const response = await fetch(base + path, { method: body === undefined ? 'GET' : 'POST', headers: { Origin: base,
    Cookie: [...cookies].map(([k,v]) => `${k}=${v}`).join('; '), 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  for (const value of response.headers.getSetCookie()) { const [key,...rest] = value.split(';')[0].split('='); cookies.set(key,rest.join('=')); }
  const data = await response.json(); if (data.state) state = data.state;
  assert.ok(response.ok && data.ok, `${path}: ${data.error?.code || response.status}`); return data;
}
async function command(command, payload) {
  await request('/api/state'); return request('/api/command', { command, payload, generation: state.generation,
    expected_revision: state.revision, idempotency_key: randomUUID() });
}
try {
  await request('/api/state'); await request('/api/session', { persona_id: 'm02' });
  const config = await readFile(new URL('../../vaults/developer/.env', import.meta.url), 'utf8');
  const code = config.match(/^AI_UNLOCK_CODE\s*=\s*([^\r\n]+)/m)?.[1]?.trim().replace(/^['"]|['"]$/g, '');
  await request('/api/ai-access', { code }); await command('settings.set', { feature: 'recommend', mode: 'live' }); enabled = true;
  const registered = await request('/api/ai', { feature: 'recommend', title: '실제 전문가 매칭 검증',
    question: '시연 종중의 토지 자료와 총회 기록을 검토할 가상 변호사 한 명을 선택해 주세요. 부동산과 종중 분쟁을 다루고 재산 자료·총회 기록 검토를 안내하는 후보가 필요합니다. 실제 사건 접수나 수임은 요청하지 않습니다.',
    profession: 'lawyer', method: '온라인', evidence: [{ document_id: 'doc03', version: 1 }],
    generation: state.generation, idempotency_key: randomUUID() });
  const { job } = await request('/api/ai', { action: 'run', id: registered.object_id, generation: state.generation });
  await mkdir(new URL('../../qa/artifacts/live/', import.meta.url), { recursive: true });
  await writeFile(new URL('../../qa/artifacts/live/matching-result.json', import.meta.url), JSON.stringify({
    at: new Date().toISOString(), commit: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(),
    job_id: job.id, status: job.status, model: job.model, routing: job.routing, prompt_version: job.prompt_version, reused: registered.reused,
    newly_executed_model_requests: registered.reused ? 0 : job.calls.length,
    candidates: job.input.candidates.map(x => ({ id: x.id, name: x.name })), result: job.result, calls: job.calls, error: job.error,
  }, null, 2));
  assert.equal(job.status, 'completed'); assert.ok(job.prompt_version.endsWith('matching-v4'));
  assert.ok(['matched', 'no_suitable_candidate', 'needs_information'].includes(job.result.matching.status));
  assert.ok(job.calls.length >= 1 && job.calls.length <= 3); assert.ok(job.calls.every(x => !/astra/i.test(x.model || '')));
  if (job.result.matching.status === 'matched') assert.ok(job.input.candidates.some(x => x.id === job.result.matching.expert_id));
  console.log(JSON.stringify({ status: job.status, matching: job.result.matching, model_requests: job.calls.length, model: job.model }));
} finally {
  if (enabled) await command('settings.set', { feature: 'recommend', mode: 'mock' });
}
