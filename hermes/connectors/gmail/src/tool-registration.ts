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
    get_profile: "연결한 Gmail 계정의 주소와 메일·스레드 수를 읽습니다.",
    list_labels: "라벨의 ID, 이름, 종류를 읽습니다.",
    search_messages:
      "Gmail 검색어로 메일 요약을 찾습니다. 예: query에 from:news@example.com을 넣습니다.",
    get_message: "message_id로 메일 한 통의 머리, 본문, 첨부 목록을 읽습니다.",
    get_thread: "thread_id로 스레드의 메일을 순서대로 읽습니다.",
    create_draft:
      "승인 후 초안을 만듭니다. reply_to_message_id를 주면 같은 스레드에 답장 초안을 만듭니다.",
    modify_labels:
      "승인 후 메일 한 통의 라벨을 바꿉니다. 예: remove_labels에 INBOX를 넣어 보관합니다.",
    send_message:
      "승인 후 새 메일을 보냅니다. 결과 불명은 보낸편지함에서 확인합니다.",
    reply_to_message: "승인 후 원래 메일의 스레드에 답장을 보냅니다.",
    create_label: "승인 후 사용자 라벨을 만듭니다.",
    update_label: "승인 후 사용자 라벨의 이름이나 색을 바꿉니다.",
    list_filters: "저장된 Gmail 필터와 조건, 동작을 읽습니다.",
    create_filter:
      "매번 승인 후 새 메일 필터를 만듭니다. 전달 동작은 허용하지 않습니다.",
    delete_filter: "매번 승인 후 filter_id의 필터를 지웁니다.",
    apply_labels_to_query:
      "매번 승인 후 검색한 기존 메일의 라벨을 바꿉니다. expected_count에 승인한 정확한 대상 수를 넣습니다.",
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
