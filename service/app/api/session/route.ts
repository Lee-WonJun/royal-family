import { demoPersona, personas, cookie, accessStatus } from "../access";
import { readState, json, requireSameOrigin, AppError, errorResponse } from "../store";
export async function GET(request: Request) {
  try { return json({ ok: true, personas, session: demoPersona(request), access: await accessStatus(request, await readState()) }); }
  catch (e) { return errorResponse(e); }
}
export async function POST(request: Request) {
  try {
    requireSameOrigin(request);
    const body = await request.json() as { persona_id?: string };
    const persona = personas.find(p => p.id === body.persona_id);
    if (!persona) throw new AppError("invalid_input", "시연 계정을 선택해 주세요.");
    const response = json({ ok: true, session: persona });
    response.headers.append("Set-Cookie", cookie(request, "rf_demo_persona", persona.id));
    response.headers.append("Set-Cookie", cookie(request, "rf_ai_grant", "", 0));
    return response;
  } catch (e) { return errorResponse(e); }
}
export async function DELETE(request: Request) {
  try {
    requireSameOrigin(request);
    const response = json({ ok: true });
    response.headers.append("Set-Cookie", cookie(request, "rf_demo_persona", "", 0));
    response.headers.append("Set-Cookie", cookie(request, "rf_ai_grant", "", 0));
    return response;
  } catch (e) { return errorResponse(e); }
}
