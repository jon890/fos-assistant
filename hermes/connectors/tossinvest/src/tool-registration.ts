import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { guard } from "./errors.ts";

export type RegisterTool = (
  name: string,
  schema: any,
  annotations: any,
  run: (input: any) => Promise<unknown>,
) => void;

export function createToolRegistration(server: McpServer) {
  const descriptions: Record<string, string> = {
    list_accounts:
      "연결한 토스증권 계좌의 순번과 유형, 끝 네 자리를 읽습니다.",
    get_quotes:
      "종목 코드나 티커 20개까지의 현재가와 이름을 읽습니다. 예: symbols 에 005930,AAPL 을 넣습니다.",
  };
  const register: RegisterTool = (
    name: string,
    schema: any,
    annotations: any,
    run: (input: any) => Promise<unknown>,
  ) =>
    server.registerTool(
      name,
      {
        description: `${descriptions[name]} 필요한 인자와 결과를 확인한 뒤 사용합니다.`,
        inputSchema: schema,
        annotations,
      },
      (input: any) => guard(() => run(input)),
    );
  return register;
}
