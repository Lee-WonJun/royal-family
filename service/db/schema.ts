import { sqliteTable, text, integer } from "drizzle-orm/sqlite-core";

// One atomic aggregate for the small demonstration workspace. Domain modules
// own their JSON sections; the application owns optimistic concurrency.
export const workspaces = sqliteTable("workspaces", {
  id: text("id").primaryKey(),
  revision: integer("revision").notNull().default(0),
  generation: integer("generation").notNull().default(1),
  body: text("body").notNull(),
});
