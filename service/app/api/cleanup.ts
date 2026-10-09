import { env } from 'cloudflare:workers';
import { readState, runInternal, workspaceId, AppError } from './store';
import { createOpenAI, AiProviderError } from '../../connectors/openai/client.mjs';

function validResource(resource: any) {
  return resource.kind === 'vector_store' ? /^vs_[a-zA-Z0-9_-]+$/.test(resource.id) : resource.kind === 'file' && /^file[-_][a-zA-Z0-9_-]+$/.test(resource.id);
}
export async function recordResource(resource: { kind: string; id: string }, status: string, generation: number, jobId: string) {
  if (!validResource(resource)) throw new AppError('invalid_input', 'OpenAI 자원 식별자를 확인하지 못했습니다.');
  const state = await readState(), id = `${resource.kind}:${resource.id}`;
  await runInternal('resource.track', { kind: resource.kind, resource_id: resource.id, source_generation: generation, job_id: jobId },
    state.generation, `track:${id}`, id);
  if (status !== 'active') {
    const current = await readState();
    await runInternal('resource.update', { id, status }, current.generation, `resource:${status}:${id}:${crypto.randomUUID()}`);
  }
}
export async function flushCleanup() {
  const initial = await readState();
  const pending = (initial.resources || []).filter((resource: any) => resource.status === 'pending' || resource.status === 'active' &&
    (!initial.jobs.some((job: any) => job.id === resource.job_id && job.status === 'running') ||
      Date.now() - Date.parse(resource.created_at) > 300000)).slice(0, 2);
  for (const resource of pending) {
    try {
      if (resource.kind === 'r2_generation') {
        const current = await readState();
        if (!Number.isInteger(resource.source_generation) || resource.source_generation < 1 || resource.source_generation >= current.generation) throw new Error('Invalid cleanup generation');
        if (!env.BUCKET) throw new Error('Storage unavailable');
        const prefix = `${workspaceId}/${resource.source_generation}/`;
        const page = await env.BUCKET.list({ prefix, limit: 100, ...(resource.cursor ? { cursor: resource.cursor } : {}) });
        const keys = page.objects.map(object => object.key);
        if (keys.some(key => !key.startsWith(prefix))) throw new Error('Invalid cleanup scope');
        if (keys.length) await env.BUCKET.delete(keys);
        const latest = await readState();
        await runInternal('resource.update', { id: resource.id, status: page.truncated ? 'pending' : 'done', cursor: page.truncated ? page.cursor : null }, latest.generation, `cleanup:${crypto.randomUUID()}`);
      } else {
        // General regression tests never call OpenAI, including cleanup requests.
        if (env.RF_FORCE_MOCK === '1') continue;
        const reference = { kind: resource.kind, id: resource.resource_id };
        if (!validResource(reference)) throw new Error('Invalid cleanup resource');
        const client = createOpenAI({ apiKey: env.OPENAI_API_KEY, projectId: env.OPENAI_PROJECT_ID, timeoutMs: 5000, jobId: `cleanup-${resource.job_id}` });
        try { await client.request(`/${resource.kind === 'file' ? 'files' : 'vector_stores'}/${resource.resource_id}`, { method: 'DELETE' }); }
        catch (error) { if (!(error instanceof AiProviderError) || error.status !== 404) throw error; }
        const latest = await readState();
        await runInternal('resource.update', { id: resource.id, status: 'done', error: null }, latest.generation, `cleanup:${crypto.randomUUID()}`);
      }
    } catch {
      const latest = await readState();
      await runInternal('resource.update', { id: resource.id, status: 'failed', error: 'cleanup_failed' }, latest.generation, `cleanup-failed:${crypto.randomUUID()}`).catch(() => undefined);
    }
  }
}
