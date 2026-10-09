declare module "*/generated/ui/main.js" {
  import type { ComponentType } from "react";
  export const App: ComponentType;
  export function configureMapLoader(loader: () => Promise<unknown>): void;
}
declare module "*/generated/domain/main.js" {
  export function initialState(generation?: number): Record<string, any>;
  export function execute(state: unknown, context: unknown, command: unknown): any;
  export function query(state: unknown, context: unknown, query: unknown): any;
  export function parcelFromKgeop(response: unknown, pnu: string, retrievedAt: string):
    { ok: true; value: Record<string, unknown> } | { ok: false; error: { code: string; message: string } };
}
