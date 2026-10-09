import { readState, publicState, json, errorResponse } from "../store";
import { demoPersona, personas, accessStatus } from "../access";
export async function GET(request: Request) {
  try {
    const state = await readState();
    return json({ ok: true, state: publicState(state), personas, session: demoPersona(request), access: await accessStatus(request, state), capabilities: { live: false, external_calls: "blocked" } });
  }
  catch (e) { return errorResponse(e); }
}
