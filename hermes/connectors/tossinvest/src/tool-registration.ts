import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { guard } from "./errors.ts";
import { z } from "zod";
import manifest from "../connector.json";

export type RegisterTool = (
  name: string,
  schema: any,
  annotations: any,
  run: (input: any) => Promise<unknown>,
) => void;

export function createToolRegistration(server: McpServer) {
  const register: RegisterTool = (
    name: string,
    schema: any,
    annotations: any,
    run: (input: any) => Promise<unknown>,
  ) =>
    server.registerTool(
      name,
      {
        description: (
          manifest.tools as Record<string, { description: string }>
        )[name]?.description,
        inputSchema: z.strictObject(schema),
        annotations,
      },
      (input: any) => guard(() => run(input)),
    );
  return register;
}
