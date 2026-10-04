import { ExecutionNode } from "./execution-node";

/** Hermes 사건을 우리 이름으로 옮겨 적은 값이다. 화면은 Hermes 의 원래 사건 이름을 읽지 않는다. */
export type ExecutionEventType =
  | "RUN_STARTED"
  | "RUN_COMPLETED"
  | "RUN_CANCELLED"
  | "RUN_FAILED"
  | "TOOL_STARTED"
  | "TOOL_COMPLETED"
  | "SUBAGENT_STARTED"
  | "SUBAGENT_COMPLETED"
  | "PROVIDER_SWITCHED";

export type ExecutionEventView = {
  sequence: number;
  eventType: ExecutionEventType;
  toolName: string | null;
  subagentName: string | null;
  hermesSessionId: string | null;
  durationMs: number | null;
  failed: boolean | null;
  detail: string | null;
  model: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  occurredAt: string;
  /** 비동기 하위 에이전트 사용량을 뒤늦게 읽는 상태다. 실행 성공 여부가 아니다. */
  subagentUsageStatus?: "WAITING" | "RECORDED" | "UNCONFIRMED" | null;
};

/** 실행 하나의 문맥에 실은 항목의 참조다. 제목과 본문은 오지 않는다. */
export type ExecutionContextSource = {
  source: string;
  ref: string;
  bodyMode: string;
  freshness: string;
};

/**
 * 트리의 노드 하나다.
 *
 * @see truncated 이 노드 아래를 잘랐다는 뜻이다. 자식 자리에 한 줄로 그린다.
 * @see contextSources 관리자에게만 온다. 그 밖에는 null 이다.
 */
export type ExecutionTreeNode = {
  truncated: boolean;
  executionId: number;
  agentCode: string | null;
  agentName: string | null;
  status: string;
  provider?: string | null;
  model: string | null;
  inputTokens: number | null;
  cachedInputTokens?: number | null;
  outputTokens: number | null;
  totalTokens?: number | null;
  estimatedCostMicros: number | null;
  latencyMs: number | null;
  startedAt: string;
  reasoningEffort?: string | null;
  reasoningEffortSource?:
    "REQUESTED" | "AGENT_DEFAULT" | "PROFILE_DEFAULT" | "UNKNOWN" | null;
  modelTier?: "FAST" | "BALANCED" | "DEEP" | null;
  requestReceivedAt?: string | null;
  submittedAt?: string | null;
  firstDeltaAt?: string | null;
  finishedAt?: string | null;
  contextSources?: ExecutionContextSource[] | null;
  events: ExecutionEventView[];
  children: ExecutionTreeNode[];
};

/**
 * 실행 하나가 속한 트리 전체다.
 *
 * <p>`truncated` 는 트리 어딘가를 잘랐다는 뜻이라 자리를 가리키지 않는다. 트리 안 어느 노드도
 * `truncated` 가 아닌데 이 값만 참이면, 루트로 올라가는 길이 잘려 지금 보이는 루트가 진짜 루트가
 * 아닐 수 있다는 뜻이다({@link ExecutionTreeNode.truncated} 는 아래로 내려가는 길만 가리킬 수 있어서
 * 위쪽이 잘린 자리를 담지 못한다). 화면은 그때 루트 위에 안내를 한 줄 그린다.
 */
export type ExecutionTreeResponse = {
  root: ExecutionTreeNode;
  truncated: boolean;
};

/**
 * 화면이 그릴 것이 트리 안에 하나라도 있는지 본다.
 *
 * <p>자식 노드가 있으면 그 자체가 그릴 내용이라 더 보지 않는다. `RUN_STARTED` 와 `RUN_COMPLETED` 는
 * 머리의 상태와 걸린 시간이 이미 말하므로 세지 않는다.
 */
export function hasRenderableEvent(node: ExecutionTreeNode): boolean {
  if (node.children.length > 0) return true;
  return node.events.some(
    (event) =>
      event.eventType !== "RUN_STARTED" && event.eventType !== "RUN_COMPLETED",
  );
}

/** 트리 안 어느 노드가 `truncated` 인지 자식까지 훑는다. */
function anyNodeTruncated(node: ExecutionTreeNode): boolean {
  return node.truncated || node.children.some(anyNodeTruncated);
}

/**
 * 트리를 그린다. `isAdmin` 이면 오류 코드와 도구 결과의 원본 같은 내부 값도 함께 그린다.
 *
 * <p>`showRuntime` 이면 노드마다 모델 제공사와 모델과 토큰을 그린다. 실행 상세가 관리자에게만 켠다.
 * 대화 안 작업 과정은 켜지 않아 역할과 관계없이 단계 이름과 걸린 시간만 보인다.
 */
export function ExecutionTree({
  tree,
  isAdmin,
  showRuntime = false,
}: {
  tree: ExecutionTreeResponse;
  isAdmin: boolean;
  showRuntime?: boolean;
}) {
  // 트리의 truncated 가 참인데 그 안 어느 노드도 truncated 가 아니면, 아래쪽이 아니라 루트로
  // 올라가는 길이 잘린 것이다. 그 경우에만 위쪽 안내를 그린다 — 노드가 이미 「여기부터 보이지
  // 않는다」 를 그렸으면 여기서 또 적어 두 번 말하지 않는다.
  const truncatedAbove = tree.truncated && !anyNodeTruncated(tree.root);
  const aboveNotice = truncatedAbove ? (
    <p
      className="mb-2 text-xs text-muted-foreground"
      data-testid="execution-tree-truncated-above"
    >
      위쪽 기록이 없어 이곳이 첫 실행이 아닐 수 있어요
    </p>
  ) : null;

  if (!hasRenderableEvent(tree.root)) {
    return (
      <>
        {aboveNotice}
        <p className="text-sm text-muted-foreground">기록된 작업이 없어요</p>
      </>
    );
  }
  return (
    <>
      {aboveNotice}
      <ul className="min-w-0" data-testid="execution-tree">
        <ExecutionNode
          node={tree.root}
          depth={0}
          isAdmin={isAdmin}
          showRuntime={showRuntime}
        />
      </ul>
    </>
  );
}
