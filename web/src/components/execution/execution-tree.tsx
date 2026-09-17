import { ExecutionNode } from "./execution-node";

/** Hermes 사건을 우리 이름으로 옮겨 적은 값이다. `docs/adr/ADR-013` 근거로 화면은 이 일곱 값만 안다. */
export type ExecutionEventType =
  | "RUN_STARTED"
  | "RUN_COMPLETED"
  | "RUN_FAILED"
  | "TOOL_STARTED"
  | "TOOL_COMPLETED"
  | "SUBAGENT_STARTED"
  | "SUBAGENT_COMPLETED";

export type ExecutionEventView = {
  sequence: number;
  eventType: ExecutionEventType;
  toolName: string | null;
  subagentName: string | null;
  durationMs: number | null;
  detail: string | null;
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

/** 실행 하나가 속한 나무 전체다. `truncated` 는 나무 어딘가를 잘랐다는 뜻이라 자리를 가리키지 않는다. */
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

export function ExecutionTree({ tree }: { tree: ExecutionTreeResponse }) {
  if (!hasRenderableEvent(tree.root)) {
    return <p className="text-sm text-muted">기록된 사건이 없다</p>;
  }
  return (
    <ul className="min-w-0" data-testid="execution-tree">
      <ExecutionNode node={tree.root} depth={0} />
    </ul>
  );
}
