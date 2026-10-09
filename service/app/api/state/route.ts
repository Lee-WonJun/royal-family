import { readState, publicState, json, errorResponse } from "../store";
export async function GET() {
  try { return json({ ok: true, state: publicState(await readState()), capabilities: { live: false, external_calls: "blocked" } }); }
  catch (e) { return errorResponse(e); }
}
