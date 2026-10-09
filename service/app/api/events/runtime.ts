import { env } from 'cloudflare:workers';
import { z } from 'zod';
import { readState, runInternal, AppError, workspaceId, type State } from '../store';
import { callbackUrl } from '../../../connectors/mcp/events/network.mjs';
import { CallbackError, constantEqual, signingKey, seal, unseal, subscriptionId, normalizedArguments, signatureHeaders, canonical } from '../../../connectors/mcp/events/security.mjs';
import { pinnedPost } from '../../../connectors/mcp/events/transport';
import { expiry, verifyCallback, deliveryOutcome } from '../../../connectors/mcp/events/protocol.mjs';

const argumentsSchema = z.object({ clan_id: z.literal('demo_a'), asset_id: z.string().min(1).max(200), fields: z.array(z.enum(['owner_name', 'owner_type', 'area_m2', 'land_category'])).min(1).max(20).optional() }).strict();
const deliverySchema = z.object({ mode: z.literal('webhook'), url: z.string().url().max(2000), secret: z.string().max(100).optional() }).strict();
const schema = z.object({ name: z.literal('asset.record.updated'), arguments: argumentsSchema, delivery: deliverySchema,
  cursor: z.null().optional(), ttlMs: z.number().int().positive().nullable().optional() }).strict();
const key = () => {
  if (!env.MCP_ENCRYPTION_KEY) throw new AppError('external_unavailable', '이벤트 서명 저장 설정이 필요합니다.', 503);
  return env.MCP_ENCRYPTION_KEY;
};
function authorized(state: State, owner: string, arguments_: any) {
  if (!owner || owner.length > 512 || arguments_.clan_id !== workspaceId || state.event_access?.[owner] === false || !state.assets.items.some((asset: any) => asset.id === arguments_.asset_id))
    throw new AppError('forbidden', '구독 대상의 접근 권한이 없습니다.', 403);
}
function enabled(state: State) {
  if (env.RF_FORCE_MOCK === '1' || state.settings.features.events !== 'live') throw new AppError('external_unavailable', '설정에서 ChatGPT 알림 실제 전달을 켜 주세요.', 409);
  key();
}
export async function changeSubscription(owner: string, raw: unknown, stop = false) {
  const parsed = schema.safeParse(raw);
  if (!parsed.success) throw new AppError('invalid_input', '이벤트 구독 입력을 확인해 주세요.');
  const input = parsed.data, state = await readState();
  authorized(state, owner, input.arguments);
  const url = callbackUrl(input.delivery.url).href, args = normalizedArguments(input.arguments);
  const id = await subscriptionId(owner, url, input.name, args);
  const previous = state.subscriptions.find((sub: any) => sub.id === id && sub.owner_id === owner);
  if (stop) {
    await runInternal('subscription.stop', { id, owner_id: owner }, state.generation, `unsubscribe:${crypto.randomUUID()}`);
    return {};
  }
  enabled(state);
  if (!input.delivery.secret) throw new CallbackError('invalid_secret');
  signingKey(input.delivery.secret);
  let verifiedAt = state.subscriptions.find((sub: any) => sub.owner_id === owner && sub.callback_url === url &&
    Date.now() - Date.parse(sub.verified_at) < 5 * 60 * 1000)?.verified_at;
  if (!verifiedAt) {
    verifiedAt = await verifyCallback({ url, secret: input.delivery.secret, id, post: pinnedPost });
  }
  const refreshBefore = expiry(input.ttlMs);
  let priorSecret = previous?.previous_encrypted_secret || null, rotationUntil = previous?.rotation_until || null;
  if (previous && !constantEqual(input.delivery.secret, await unseal(previous.encrypted_secret, key(), id))) {
    priorSecret = previous.encrypted_secret; rotationUntil = new Date(Date.now() + 5 * 60 * 1000).toISOString();
  }
  await runInternal('subscription.upsert', { id, owner_id: owner, name: input.name, arguments: args, callback_url: url,
    callback_host: new URL(url).hostname, encrypted_secret: await seal(input.delivery.secret, key(), id), previous_encrypted_secret: priorSecret,
    rotation_until: rotationUntil, verified_at: verifiedAt, expires_at: refreshBefore }, state.generation, `subscribe:${crypto.randomUUID()}`);
  return { id, refreshBefore, cursor: null, truncated: false };
}

async function deliveryContext(id: string, generation: number) {
  const state = await readState();
  if (state.generation !== generation) throw new AppError('stale_generation', '초기화 전 이벤트입니다.', 409);
  enabled(state);
  const delivery = state.deliveries?.find((entry: any) => entry.id === id);
  const sub = state.subscriptions.find((entry: any) => entry.id === delivery?.subscription_id);
  if (!delivery || !sub || !['pending', 'delivering', 'retry_wait'].includes(delivery.status) || sub.status !== 'active' || sub.generation !== generation || Date.parse(sub.expires_at) <= Date.now())
    throw new AppError('closed', '구독 또는 전달이 종료되었습니다.', 409);
  authorized(state, sub.owner_id, sub.arguments);
  const event = state.outbox.find((entry: any) => entry.id === delivery.event_id)?.event;
  if (!event || event.data.asset_id !== sub.arguments.asset_id || !event.data.changed_fields.some((field: string) => sub.arguments.fields.includes(field)))
    throw new AppError('closed', '구독 필터가 일치하지 않습니다.', 409);
  return { state, delivery, sub, event };
}
async function sendDelivery(id: string, generation: number) {
  for (let index = 0; index < 3; index++) {
    let context;
    try { context = await deliveryContext(id, generation); }
    catch { await runInternal('delivery.stop', { id, reason: 'subscription_or_mode_changed' }, generation, `stop:${id}`).catch(() => undefined); return; }
    if (context.delivery.status === 'delivering' || context.delivery.attempts >= 3) return;
    const lease = crypto.randomUUID(), body = context.delivery.body || canonical(context.event);
    if (new TextEncoder().encode(body).length > 262144) {
      await runInternal('delivery.stop', { id, reason: 'payload_too_large' }, generation, `oversize:${id}`).catch(() => undefined); return;
    }
    try { await runInternal('delivery.update', { id, action: 'claim', lease, body }, generation, `claim:${lease}`); }
    catch { return; }
    let status = 0, reason: string | null = null;
    try {
      const current = await deliveryContext(id, generation);
      const secrets = [await unseal(current.sub.encrypted_secret, key(), current.sub.id)];
      if (current.sub.previous_encrypted_secret && Date.parse(current.sub.rotation_until) > Date.now()) secrets.push(await unseal(current.sub.previous_encrypted_secret, key(), current.sub.id));
      const headers = await signatureHeaders(secrets, current.event.eventId, body, current.sub.id);
      const response = await pinnedPost(current.sub.callback_url, body, headers, 5000, async () => { await deliveryContext(id, generation); });
      status = response.status;
    } catch (error) { reason = error instanceof CallbackError ? error.reason : 'delivery_cancelled'; }
    const delivered = status >= 200 && status < 300;
    const next = deliveryOutcome(status, reason, context.delivery.attempts + 1);
    await runInternal('delivery.update', { id, action: 'finish', lease, status: next, http_status: status || null,
      reason: delivered ? null : reason || `http_${status}` }, generation, `finish:${lease}`).catch(() => undefined);
    if (status === 410) await runInternal('subscription.stop', { id: context.sub.id, owner_id: context.sub.owner_id }, generation, `gone:${id}`).catch(() => undefined);
    if (next !== 'retry_wait') return;
    await new Promise(resolve => setTimeout(resolve, 1000 * (index + 1) + Math.floor(Math.random() * 200)));
  }
}

export async function flushEvents() {
  if (env.RF_FORCE_MOCK === '1' || !env.MCP_ENCRYPTION_KEY) return;
  const state = await readState();
  if (state.settings.features.events !== 'live') return;
  for (const delivery of state.deliveries || []) {
    if (delivery.status === 'delivering' && Date.now() - Date.parse(delivery.last_attempt_at) > 15000)
      await runInternal('delivery.interrupted', { id: delivery.id }, state.generation, `interrupted:${delivery.id}:${delivery.lease}`).catch(() => undefined);
  }
  const selected = (state.deliveries || []).filter((entry: any) => ['pending', 'retry_wait'].includes(entry.status)).slice(0, 2);
  await Promise.all(selected.map((entry: any) => sendDelivery(entry.id, state.generation)));
}
