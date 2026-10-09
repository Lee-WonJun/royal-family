import { env } from "cloudflare:workers";
import personas from "../../fixtures/personas.json";
import { verifyGrant } from "./access-crypto.mjs";
import { AppError, type State } from "./store";

export { personas };
export function cookieValue(request: Request, name: string): string {
  const value = (request.headers.get("cookie") || "").split(";").map(x => x.trim()).find(x => x.startsWith(`${name}=`));
  return value?.slice(name.length + 1) || "";
}
export function demoPersona(request: Request) {
  const id = cookieValue(request, "rf_demo_persona");
  return personas.find(persona => persona.id === id) || null;
}
export function cookie(request: Request, name: string, value: string, maxAge?: number) {
  return `${name}=${value}; Path=/; HttpOnly; SameSite=Strict${new URL(request.url).protocol === "https:" ? "; Secure" : ""}${maxAge !== undefined ? `; Max-Age=${maxAge}` : ""}`;
}
export async function accessStatus(request: Request, state: State) {
  const persona = demoPersona(request);
  const code = env.AI_UNLOCK_CODE || "";
  return { configured: code.length >= 16,
    unlocked: !!persona && await verifyGrant(cookieValue(request, "rf_ai_grant"), persona.id, state.generation, code),
    ready_features: [] as string[] };
}
export async function requireAiGrant(request: Request, state: State) {
  if (!(await accessStatus(request, state)).unlocked) throw new AppError("developer_code_required", "실제 호출을 켜려면 개발자 코드를 확인해 주세요.", 403);
}
