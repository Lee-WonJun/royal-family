import { z } from "zod";
const id = z.string().min(1).max(200);
const text = z.string().max(20000);
const version = z.number().int().positive();
const docRef = z.object({ document_id: id, version }).strict();
const expected = { id, expected_version: version };
const payloads: Record<string, z.ZodTypeAny> = {
  "member.add": z.object({ name: text, role: text.optional() }).strict(),
  "member.update": z.object({ ...expected, role: text.optional(), contact_state: text.optional(), outreach: text.optional() }).strict(),
  "relation.add": z.object({ parent_id: id, child_id: id, source: text }).strict(),
  "relation.remove": z.object({ id, reason: text }).strict(),
  "document.create": z.object({ title: text, body: text, kind: text.optional(), evidence: z.array(docRef).optional() }).strict(),
  "document.revise": z.object({ ...expected, body: text }).strict(),
  "document.review": z.object({ ...expected, action: z.enum(["submit", "confirm", "reject", "resolve"]), note: text.optional() }).strict(),
  "transaction.add": z.object({ title: text, date: text, amount: z.number(), direction: z.enum(["income", "expense"]), document_id: id.optional(), corrects_id: id.optional() }).strict(),
  "meeting.create": z.object({ title: text, date: text, place: text, agenda: text, document_id: id.optional(), document_version: version.optional() }).strict(),
  "meeting.record": z.object({ ...expected, field: z.enum(["attendance", "notices", "votes"]), member_id: id, value: text, note: text.optional() }).strict(),
  "consent.create": z.object({ title: text, document_id: id, document_version: version, targets: z.array(id), deadline: text }).strict(),
  "consent.respond": z.object({ request_id: id, document_version: version, member_id: id, response: z.enum(["agree", "disagree", "withdrawn"]), note: text.optional() }).strict(),
  "notification.read": z.object({ id }).strict(),
  "asset.snapshot": z.object({ asset_id: id, parcel: text, source_kind: text, owner_name: text, owner_type: text, area_m2: z.number(), land_category: text }).strict(),
  "asset.failure": z.object({ asset_id: id }).strict(),
  "preparation.save": z.object({ task: text, held: z.array(text), note: text.optional() }).strict(),
  "consultation.prepare": z.object({ expert_id: id, documents: z.array(docRef), question: text }).strict(),
  "settings.set": z.object({ feature: text, mode: z.enum(["mock", "live"]) }).strict(),
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
