import { tools, validate } from "../../connectors/mcp/tools/catalog";
import { readState, runCommandDetailed, runQuery, json, AppError } from "../api/store";
import { commandSchema } from "../api/contracts";
import { changeSubscription } from '../api/events/runtime';
import { eventDefinition } from '../../connectors/mcp/events/catalog';
import { CallbackError } from '../../connectors/mcp/events/security.mjs';

export async function POST(request: Request) {
  let id: string | number | null = null;
  try {
    const raw = await request.text();
    if (raw.length > 180000) throw new AppError("invalid_input", "Request exceeds limit.", 413);
    const message = JSON.parse(raw);
    id = typeof message.id === "string" || typeof message.id === "number" ? message.id : null;
    if (message.jsonrpc !== "2.0" || typeof message.method !== "string") throw new AppError("invalid_input", "Invalid JSON-RPC request.");
    const respond = (result: unknown) => json({ jsonrpc: "2.0", id, result });
    if (message.method === "server/discover") return respond({ resultType: "complete", supportedVersions: ["2026-07-28"], capabilities: { tools: {}, events: {} } });
    if (message.method === "initialize") return respond({ protocolVersion: "2025-03-26", capabilities: { tools: {} }, serverInfo: { name: "royal-family", version: "0.1.0" } });
    if (message.method === "notifications/initialized") return new Response(null, { status: 202 });
    if (message.method === "ping") return respond({});
    if (!request.headers.get("oai-authenticated-user-id")) return json({ jsonrpc: "2.0", id, error: { code: -32001, message: "Sites authentication required." } }, 401);
    if (message.method === 'events/list') return respond({ events: [eventDefinition] });
    if (['events/subscribe', 'events/unsubscribe'].includes(message.method)) return respond(await changeSubscription(request.headers.get('oai-authenticated-user-id')!, message.params, message.method === 'events/unsubscribe'));
    if (message.method === "tools/list") return respond({ tools: tools.map(({ command: _command, ...tool }) => tool) });
    if (message.method !== "tools/call") return json({ jsonrpc: "2.0", id, error: { code: -32601, message: "Method not implemented." } });
    const tool = tools.find(t => t.name === message.params?.name);
    if (!tool || !validate(tool.inputSchema, message.params.arguments)) throw new AppError("invalid_input", "Invalid tool arguments.");
    const { clan_id, expected_revision, generation, idempotency_key, ...payload } = message.params.arguments;
    let state = await readState();
    let result: unknown;
    if (tool.command) {
      const cmd = commandSchema.parse({ command: tool.command, payload, expected_revision, generation, idempotency_key });
      const saved = await runCommandDetailed(cmd, { site_origin: new URL(request.url).origin });
      state = saved.state;
      result = { saved: true, is_demo: true, object_id: saved.object_id };
    } else result = runQuery(state, { query: tool.name, clan_id, ...payload });
    const value = { revision: state.revision, generation: state.generation, data: result };
    return respond({ content: [{ type: "text", text: JSON.stringify(value) }], isError: false });
  } catch (error) {
    if (error instanceof CallbackError) return json({ jsonrpc: '2.0', id, error: { code: -32015, message: 'CallbackEndpointError', data: { reason: error.reason } } });
    const known = error instanceof AppError;
    return json({ jsonrpc: "2.0", id, error: { code: known && error.code === "forbidden" ? -32001 : -32602,
      message: known ? error.message : "Invalid request or unavailable data." } }, known && error.status === 403 ? 403 : 200);
  }
}
