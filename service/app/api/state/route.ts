import { readState, publicState, json, errorResponse, readiness } from "../store";
import { demoPersona, personas, accessStatus } from "../access";
export async function GET(request: Request) {
  try {
    const state = await readState();
    const ready = readiness();
    return json({ ok: true, state: publicState(state), personas, session: demoPersona(request), access: await accessStatus(request, state),
      capabilities: { live: Object.entries(ready).some(([feature, value]) => value && state.settings.features[feature] === 'live'), external_calls: 'feature_toggles', ready_features: ready } });
  }
  catch (e) { return errorResponse(e); }
}
