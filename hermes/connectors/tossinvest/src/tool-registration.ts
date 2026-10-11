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
  const units =
    " quantity는 주 단위 수량, KR price는 원, US price와 orderAmount는 달러입니다. 수량 주문은 quantity를, US MARKET 금액 주문은 orderAmount를 사용하며 둘을 함께 넣지 않습니다.";
  const noReplay = " UNKNOWN이면 새 권한이나 UUID로 자동 재전송하지 않습니다.";
  const unsupported =
    " 금액 원주문을 LIMIT로 정정하는 것은 현재 지원하지 않습니다.";
  const descriptions: Record<string, string> = {
    prepare_trade:
      "일반 주문의 계좌와 종목, 원주문을 조회해 승인할 실행 인자와 표시 값을 준비합니다. 주문을 전송하지 않습니다." +
      units +
      unsupported,
    create_order:
      "준비한 일반 주문의 실행 원문과 일회성 권한을 검증한 뒤 주문을 한 번 전송합니다." +
      units +
      noReplay,
    modify_order:
      "준비한 정정의 실행 원문과 일회성 권한, 원주문과 체결 수량을 검증한 뒤 한 번 정정합니다." +
      units +
      unsupported +
      noReplay,
    cancel_order:
      "준비한 취소의 실행 원문과 일회성 권한, 원주문과 체결 수량을 검증한 뒤 일반 주문을 한 번 취소합니다." +
      units +
      noReplay,
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
        description:
          (manifest.tools as Record<string, { description: string }>)[name]
            ?.description ?? descriptions[name],
        inputSchema: z.strictObject(schema),
        annotations,
      },
      (input: any) => guard(() => run(input)),
    );
  return register;
}
