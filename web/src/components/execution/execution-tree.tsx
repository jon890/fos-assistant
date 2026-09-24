import { ExecutionNode } from "./execution-node";

/** Hermes 사건을 우리 이름으로 옮겨 적은 값이다. 화면은 Hermes 의 원래 사건 이름을 읽지 않는다. */
export type ExecutionEventType =
  | "RUN_STARTED"
  | "RUN_COMPLETED"
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
  durationMs: number | null;
  detail: string | null;
  model: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  occurredAt: string;
};

/**
 * 나무의 노드 하나다.
 *
 * @see truncated 이 노드 아래를 잘랐다는 뜻이다. 자식 자리에 한 줄로 그린다.
 */
export type ExecutionTreeNode = {
  truncated: boolean;
  executionId: number;
  agentCode: string | null;
  agentName: string | null;
  status: string;
  model: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  estimatedCostMicros: number | null;
  latencyMs: number | null;
  startedAt: string;
  events: ExecutionEventView[];
  children: ExecutionTreeNode[];
};

/**
 * 실행 하나가 속한 나무 전체다.
 *
 * <p>`truncated` 는 나무 어딘가를 잘랐다는 뜻이라 자리를 가리키지 않는다. 나무 안 어느 노드도
 * `truncated` 가 아닌데 이 값만 참이면, 뿌리로 올라가는 길이 잘려 지금 보이는 뿌리가 진짜 뿌리가
 * 아닐 수 있다는 뜻이다({@link ExecutionTreeNode.truncated} 는 아래로 내려가는 길만 가리킬 수 있어서
 * 위쪽이 잘린 자리를 담지 못한다). 화면은 그때 뿌리 위에 안내를 한 줄 그린다.
 */
export type ExecutionTreeResponse = {
  root: ExecutionTreeNode;
  truncated: boolean;
};

/**
 * 화면이 그릴 것이 나무 안에 하나라도 있는지 본다.
 *
 * <p>자식 노드가 있으면 그 자체가 그릴 내용이라 더 보지 않는다. `RUN_STARTED` 와 `RUN_COMPLETED` 는
 * 머리의 상태와 걸린 시간이 이미 말하므로 세지 않는다.
 */
export function hasRenderableEvent(node: ExecutionTreeNode): boolean {
  if (node.children.length > 0) return true;
  return node.events.some(
    (event) => event.eventType !== "RUN_STARTED" && event.eventType !== "RUN_COMPLETED",
  );
}

/** 나무 안 어느 노드가 `truncated` 인지 자식까지 훑는다. */
function anyNodeTruncated(node: ExecutionTreeNode): boolean {
  return node.truncated || node.children.some(anyNodeTruncated);
}

export function ExecutionTree({ tree }: { tree: ExecutionTreeResponse }) {
  // 나무의 truncated 가 참인데 그 안 어느 노드도 truncated 가 아니면, 아래쪽이 아니라 뿌리로
  // 올라가는 길이 잘린 것이다. 그 경우에만 위쪽 안내를 그린다 — 노드가 이미 「여기부터 보이지
  // 않는다」 를 그렸으면 여기서 또 적어 두 번 말하지 않는다.
  const truncatedAbove = tree.truncated && !anyNodeTruncated(tree.root);
  const aboveNotice = truncatedAbove ? (
    <p className="mb-2 text-xs text-muted" data-testid="execution-tree-truncated-above">
      위쪽이 잘려 여기가 뿌리가 아닐 수 있다
    </p>
  ) : null;

  if (!hasRenderableEvent(tree.root)) {
    return (
      <>
        {aboveNotice}
        <p className="text-sm text-muted">기록된 사건이 없다</p>
      </>
    );
  }
  return (
    <>
      {aboveNotice}
      <ul className="min-w-0" data-testid="execution-tree">
        <ExecutionNode node={tree.root} depth={0} />
      </ul>
    </>
  );
}
