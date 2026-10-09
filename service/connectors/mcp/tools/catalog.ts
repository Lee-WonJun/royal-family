// Transport schemas only. All business decisions are made by CLJS modules.
type JsonSchema = Record<string, any>;
const string = { type: "string" };
const integer = { type: "integer", minimum: 1 };
const reference = { type: "object", properties: { document_id: string, version: integer }, required: ["document_id", "version"], additionalProperties: false };
const refs = { type: "array", items: reference };
const strings = { type: "array", items: string };
const mutation = { expected_revision: { type: "integer", minimum: 0 }, generation: integer, idempotency_key: string };
function tool(name: string, description: string, properties: JsonSchema, required: string[], command?: string) {
  return { name, description, command, inputSchema: { type: "object", properties: { clan_id: { type: "string", const: "demo_a" }, ...properties, ...(command ? mutation : {}) },
    required: ["clan_id", ...required, ...(command ? Object.keys(mutation) : [])], additionalProperties: false },
    annotations: { readOnlyHint: !command, destructiveHint: false, idempotentHint: true, openWorldHint: false } };
}
export const tools = [
  tool("get_clan_overview", "시연 종중의 회의·동의·확인 항목을 조회합니다. 실제 종원 인증이 아닙니다.", {}, []),
  tool("get_member_hierarchy", "가상 종원의 상위·하위 관계와 미연결 상태를 조회합니다.", { member_id: string }, []),
  tool("get_record", "문서 원문·선택 버전·검토 상태를 조회합니다.", { id: string, version: integer }, ["id"]),
  tool("search_records", "등록 문서를 제목·본문 키워드로 검색합니다. 현재 AI 검색은 연결되지 않았습니다.", { text: string }, ["text"]),
  tool("get_consent_request", "특정 버전의 동의 요청과 시연 응답 현황을 조회합니다.", { request_id: string, member_id: string }, ["request_id"]),
  tool("check_legal_basis", "등록된 공식 법령·판례 링크와 규약을 조회합니다. 법적 판단을 확정하지 않습니다.", {}, []),
  tool("check_issues", "등록 자료의 미확인 항목·증빙 누락·변경 표시를 조회합니다.", {}, []),
  tool("recommend_experts", "직군·지역·방식·예산에 맞는 가상 전문가 후보를 조회합니다.",
    { profession: { type: "string", enum: ["judicial_scrivener", "lawyer"] }, region: string, method: string, specialty: string, remote: { type: "boolean" }, budget: { type: "number", minimum: 0 } }, ["profession"]),
  tool("get_asset_changes", "시연 토지 자료의 이전·새 버전과 변경 필드를 조회합니다. 소유권 이전 확인이 아닙니다.", { asset_id: string }, ["asset_id"]),
  tool("prepare_establishment", "보유 서류와 부족한 자료를 시연 준비 기록으로 저장합니다.", { task: string, held: strings, note: string }, ["task", "held"], "preparation.save"),
  tool("prepare_meeting", "시연 총회의 일정·안건·대상 명부를 저장합니다. 외부로 발송하지 않습니다.", { title: string, date: string, place: string, agenda: string, document_id: string, document_version: integer }, ["title", "date", "place", "agenda"], "meeting.create"),
  tool("draft_document", "지정한 제목·본문·자료 버전으로 초안을 저장합니다. 검토 완료로 처리하지 않습니다.", { title: string, body: string, kind: string, evidence: refs }, ["title", "body"], "document.create"),
  tool("record_consent", "사용자가 명시한 대상 종원·요청 버전·응답을 관리자 시연 입력으로 저장합니다. 실제 본인 인증 동의가 아닙니다.",
    { request_id: string, document_version: integer, member_id: string, response: { type: "string", enum: ["agree", "disagree", "withdrawn"] }, note: string }, ["request_id", "document_version", "member_id", "response"], "consent.respond"),
  tool("prepare_consultation", "선택한 가상 후보·문서 버전·질문을 상담 준비 기록으로 저장합니다. 외부 접수·전송은 하지 않습니다.", { expert_id: string, documents: refs, question: string }, ["expert_id", "documents", "question"], "consultation.prepare"),
];

export function validate(schema: JsonSchema, value: unknown): boolean {
  if (schema.const !== undefined && value !== schema.const) return false;
  if (schema.enum && !schema.enum.includes(value)) return false;
  if (schema.type === "object") {
    if (!value || typeof value !== "object" || Array.isArray(value)) return false;
    const obj = value as Record<string, unknown>;
    if (schema.required?.some((key: string) => !(key in obj))) return false;
    return Object.keys(obj).every(key => !!schema.properties[key] && validate(schema.properties[key], obj[key]));
  }
  if (schema.type === "array") return Array.isArray(value) && value.length <= 100 && value.every(item => validate(schema.items, item));
  if (schema.type === "string") return typeof value === "string" && value.length <= 20000;
  if (schema.type === "boolean") return typeof value === "boolean";
  if (schema.type === "number" || schema.type === "integer") return typeof value === "number" && Number.isFinite(value) &&
    (schema.type !== "integer" || Number.isSafeInteger(value)) && (schema.minimum === undefined || value >= schema.minimum);
  return false;
}
