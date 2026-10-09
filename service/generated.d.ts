declare module "*/generated/ui/main.js" {
  import type { ComponentType } from "react";
  export const App: ComponentType;
}
declare module "*/generated/domain/main.js" {
  export function initialState(generation?: number): Record<string, any>;
  export function execute(state: unknown, context: unknown, command: unknown): any;
  export function query(state: unknown, context: unknown, query: unknown): any;
}
