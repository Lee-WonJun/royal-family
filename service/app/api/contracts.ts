import { z } from "zod";
const id = z.string().min(1).max(200);
const text = z.string().max(20000);
const version = z.number().int().positive();
const docRef = z.object({ document_id: id, version }).strict();
const expected = { id, expected_version: version };
const payloads: Record<string, z.ZodTypeAny> = {
  "member.add": z.object({ name: text, role: text.optional() }).strict(),
  "member.update": z.object({ ...expected, role: text.optional(), contact_state: text.optional(), outreach: text.optional(), preferred_contact: text.optional(), contact_note: z.string().max(500).optional() }).strict(),
  "member.import": z.object({ rows: z.array(z.object({ row: z.number().int().positive(), name: z.string().max(80), role: text, phone: z.string().max(20), generation: z.number().int().positive().nullable(), lineage: z.string().max(100).nullable(), preferred_contact: text, contact_note: z.string().max(500) }).strict()).min(1).max(200) }).strict(),
  "phone.readback": z.object({ request_id: id, document_version: version, member_id: id, response_id: id.nullable(), status: z.enum(["confirmed", "correction_requested", "not_reached"]), note: text }).strict(),
  "objection.create": z.object({ meeting_id: id, meeting_version: version, member_id: id, document_id: id.optional(), document_version: version.optional(), body: text }).strict(),
  "objection.reply": z.object({ id, body: text, status: z.enum(["open", "resolved"]) }).strict(),
  "preparation.item": z.object({ ...expected, item_id: id, copy_kind: z.enum(["original", "copy", "unknown"]), issued_date: z.string().max(10), check_status: z.enum(["missing", "unverified", "checked"]), document_id: id.nullable(), document_version: version.nullable(), note: text }).strict(),
  "supplement.request": z.object({ ...expected, item_id: id, body: text }).strict(),
  "supplement.reply": z.object({ ...expected, request_id: id, body: text, status: z.enum(["replied", "resolved"]), document_id: id.nullable(), document_version: version.nullable() }).strict(),
  "organization.handover": z.object({ from_id: id, from_version: version, to_id: id, to_version: version, reason: text }).strict(),
  "relation.add": z.object({ parent_id: id, child_id: id, source: text }).strict(),
  "relation.remove": z.object({ id, reason: text }).strict(),
  "document.create": z.object({ title: text, body: text, kind: text.optional(), evidence: z.array(docRef).optional() }).strict(),
  "document.revise": z.object({ ...expected, body: text, reason: text }).strict(),
  "document.review": z.object({ ...expected, action: z.enum(["submit", "confirm", "reject", "resolve"]), note: text.optional() }).strict(),
  "transaction.add": z.object({ title: text, date: text, amount: z.number(), direction: z.enum(["income", "expense"]), document_id: id.optional(), document_version: version.optional(), corrects_id: id.optional(), reason: text.optional() }).strict(),
  "meeting.create": z.object({ title: text, date: text, place: text, agenda: text, document_id: id.optional(), document_version: version.optional(), regulation_id: id.optional(), regulation_version: version.optional() }).strict(),
  "meeting.revise": z.object({ ...expected, title: text, date: text, place: text, agenda: text, reason: text, document_id: id.optional(), document_version: version.optional(), regulation_id: id.optional(), regulation_version: version.optional() }).strict(),
  "meeting.record": z.object({ ...expected, field: z.enum(["plans", "attendance", "delegations", "notices", "reads", "votes", "opinions"]), member_id: id, value: text, note: text.optional() }).strict(),
  "consent.create": z.object({ title: text, document_id: id, document_version: version, targets: z.array(id), deadline: text }).strict(),
  "consent.respond": z.object({ request_id: id, document_version: version, member_id: id, response: z.enum(["agree", "disagree", "withdrawn"]), note: text.optional() }).strict(),
  "notification.read": z.object({ id }).strict(),
  "asset.snapshot": z.object({ asset_id: id, parcel: text, source_kind: text, owner_name: text, owner_type: text, area_m2: z.number(), land_category: text }).strict(),
  "asset.failure": z.object({ asset_id: id }).strict(),
  "asset.check": z.object({ asset_id: id, status: z.enum(["pending", "stale", "failed"]) }).strict(),
  "contract.save": z.object({ id: id.optional(), expected_version: version.optional(), asset_id: id, title: text,
    status: z.enum(["초안", "검토 중", "내부 확인", "종료"]), document_id: id, document_version: version, reason: text.optional() }).strict(),
  "asset.registry.mock-refresh": z.object({ asset_id: id, scenario: z.enum(["changed", "unchanged", "failure"]) }).strict(),
  "preparation.save": z.object({ task: text, held: z.array(text), note: text.optional() }).strict(),
  "consultation.prepare": z.object({ expert_id: id, documents: z.array(docRef), question: text }).strict(),
  "settings.set": z.object({ feature: text, mode: z.enum(["mock", "live"]) }).strict(),
  "subscription.stop": z.object({ id, revoke: z.boolean().optional() }).strict(),
  "delivery.retry": z.object({ id }).strict(),
  "cleanup.retry": z.object({}).strict(),
  "ai.mock": z.object({ feature: text, title: text, instructions: text.optional(), kind: text.optional(), evidence: z.array(docRef).optional(), fixture: z.enum(["success", "failure"]).optional() }).strict(),
  "reset": z.object({}).strict(),
  "ai.cancel": z.object({ id }).strict(),
  "ai.apply": z.object({ id, document_id: id.optional(), expected_version: version.optional() }).strict(),
};
export const commandSchema = z.object({ command: z.string().max(60), payload: z.record(z.unknown()),
  expected_revision: z.number().int().nonnegative(), generation: version,
  idempotency_key: z.string().min(1).max(128) }).strict().superRefine((command, ctx) => {
    const schema = payloads[command.command];
    if (!schema || !schema.safeParse(command.payload).success) ctx.addIssue({ code: z.ZodIssueCode.custom, message: "Invalid command payload" });
  });
