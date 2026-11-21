import type { DelegationLine } from "@/lib/delegation";
import type { ConnectorToolName } from "@/lib/tool-label";
import type { ActivityUse } from "@/lib/tool-kind";

/**
 * Control Plane 이 대화 스트림으로 보내는 사건이다.
 *
 * <p>`system` 은 대화에 알림 줄이 저장됐다는 사건이다. 글은 `text` 에, 저장된 메시지 번호는 `messageId` 에 온다.
 *
 * <p>`user` 는 대기 메시지를 합쳐 사용자 메시지로 저장했다는 사건이고 `system` 과 같은 칸을 쓴다. `pending` 은
 * 대기 줄이 바뀌었다는 사건이다. 바뀐 내용은 싣지 않으므로 받은 쪽이 대기 줄을 다시 읽는다.
 *
 * <p>`delegation` 은 대화의 turn 이 바로 맡긴 위임이 시작했거나 끝났다는 사건이다. 맡긴 줄이 `delegation` 에 온다.
 *
 * <p>`approval` 은 승인 줄이 생겼다는 사건이다. 요청 번호만 `detail` 에 오므로 받은 쪽이 승인 줄을 다시 읽는다.
 *
 * <p>`steer` 는 도는 실행에 끼워 넣은 사용자 글의 말풍선이 생겼거나 상태가 바뀌었다는 사건이다. 말풍선 번호는
 * `messageId`, 글은 `text`, 끼운 turn 의 루트 실행은 `executionId`, 상태와 자식 종류는 `steer` 에 온다
 * (ADR-20261116 / run-steer).
 *
 * <p>`image` 는 비서가 만든 그림을 대화 폴더에 저장했다는 사건이다. 결과물 폴더 안의 경로가 `detail` 에 온다. 사건의 순서는
 * 저장 순서와 다를 수 있어 받은 쪽이 묶이지 않은 그림 목록을 다시 읽는다(generated-image 설계의 PR7).
 */
export type ChatEvent = {
  type:
    | "started"
    | "delta"
    | "interim"
    | "tool"
    | "subagent"
    | "switched"
    | "reset"
    | "done"
    | "stopped"
    | "error"
    | "system"
    | "user"
    | "pending"
    | "approval"
    | "delegation"
    | "steer"
    | "image";
  text?: string | null;
  alreadyStreamed?: boolean | null;
  streamedPrefixLength?: number | null;
  interimId?: string | null;
  toolName?: string | null;
  /** 플러그인 도구의 `tool` 사건이 싣는 서비스 이름과 도구 제목이다 */
  connector?: ConnectorToolName | null;
  detail?: string | null;
  conversationId?: string | null;
  messageId?: number | null;
  executionId?: number | null;
  code?: string | null;
  message?: string | null;
  phase?: "started" | "completed" | null;
  durationMs?: number | null;
  failed?: boolean | null;
  subagentId?: string | null;
  goal?: string | null;
  model?: string | null;
  inputTokens?: number | null;
  outputTokens?: number | null;
  /** `delegation` 사건의 맡긴 줄이다 */
  delegation?: DelegationLine | null;
  /** 위임 결과 알림 줄의 `system` 사건이 싣는 그 줄이 전한 결과다 */
  deliveredResults?: DelegationLine[] | null;
  /** Hermes 내장 위임 결과 알림 줄의 `system` 사건이 싣는 결과 상태다 */
  nativeResult?: NativeResult | null;
  /** `steer` 사건의 말풍선 상태와 받은 순간 돈 자식의 종류다 */
  steer?: SteerInfo | null;
};

/** Hermes 내장 위임 결과 알림 줄의 상태다(ADR-20261117 / native-delegation-wake) */
export type NativeResult = { status: "SUCCEEDED" | "FAILED" };

/** steer 말풍선의 상태다. `ChatDtos.MessageView.steer` 와 `steer` 사건이 같은 꼴로 싣는다 */
export type SteerInfo = {
  state: "DELIVERED" | "CARRIED_OVER" | "UNCONFIRMED" | "NOT_DELIVERED";
  child: "SUBAGENT" | "DELEGATION" | null;
};

export type ActivitySummary = {
  toolCount: number;
  subagentCount: number;
  durationMs: number | null;
  /** 쓴 도구와 도우미를 처음 쓴 차례로 한 번씩. 옛 Control Plane 은 보내지 않는다 */
  uses?: ActivityUse[];
};
