import { env } from "cloudflare:workers";
import { cookie, demoPersona, accessStatus } from "../access";
import { issueGrant, verifyDeveloperCode } from "../access-crypto.mjs";
import { readState, json, requireSameOrigin, errorResponse, AppError } from "../store";

export async function POST(request: Request) {
  try {
    requireSameOrigin(request);
    const persona = demoPersona(request);
    if (!persona) throw new AppError("session_required", "먼저 시연 계정을 선택해 주세요.", 401);
    const text = await request.text();
    if (text.length > 512) throw new AppError("invalid_input", "코드를 확인해 주세요.");
    const { code } = JSON.parse(text);
    if (!await verifyDeveloperCode(code, env.AI_UNLOCK_CODE)) throw new AppError("invalid_code", "코드를 확인해 주세요.", 403);
    const state = await readState();
    const token = await issueGrant(persona.id, state.generation, env.AI_UNLOCK_CODE);
    const response = json({ ok: true, access: { configured: true, unlocked: true, expires_at: Number(token.split(":")[2]), ready_features: [] } });
    response.headers.append("Set-Cookie", cookie(request, "rf_ai_grant", token, 1800));
    return response;
  } catch (e) { return errorResponse(e); }
}
export async function DELETE(request: Request) {
  try {
    requireSameOrigin(request);
    const response = json({ ok: true, access: { ...(await accessStatus(request, await readState())), unlocked: false, expires_at: null } });
    response.headers.append("Set-Cookie", cookie(request, "rf_ai_grant", "", 0));
    return response;
  } catch (e) { return errorResponse(e); }
}
