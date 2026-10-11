import manifest from "../connector.json";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { guard } from "./errors.ts";

export type RegisterTool = (
  name: string,
  schema: any,
  annotations: any,
  run: (input: any) => Promise<unknown>,
) => void;

export function createToolRegistration(server: McpServer) {
  const descriptions: Record<string, { description: string }> = manifest.tools;
  const register: RegisterTool = (
    name: string,
    schema: any,
    annotations: any,
    run: (input: any) => Promise<unknown>,
  ) =>
    server.registerTool(
      name,
      {
        description: descriptions[name]!.description,
        inputSchema: schema,
        annotations,
      },
      (input: any) => guard(() => run(input)),
    );
  return register;
}
