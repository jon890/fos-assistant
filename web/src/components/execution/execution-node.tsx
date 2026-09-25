import { ExecutionEventRow, type MergedEventRow } from "./execution-event-row";
import type { ExecutionEventView, ExecutionTreeNode } from "./execution-tree";

/**
 * 서버가 여덟에서 자르지만(`ExecutionTreeService.MAX_DEPTH`), 화면도 스스로 멈춘다.
 *
 * <p>**서버의 값과 같아야 한다.** 서버가 더 깊이 내려 보내면 화면이 그리지 않는 깊이에
 * `truncated` 가 실려, 위쪽 안내도 아래쪽 줄도 없이 조용히 잘린 나무가 된다.
 * 한쪽을 바꾸면 다른 쪽도 바꾼다.
 */
const MAX_DEPTH = 8;

/**
 * `TOOL_STARTED` 를 그 뒤의 `TOOL_COMPLETED` 와 짝지어 한 줄로 만든다.
 *
 * <p>짝이 없으면 그대로 한 줄로 남는다. 도구 이름별로 큐를 둬 겹친 호출도 순서대로 짝짓는다.
 *
 * <p>`SUBAGENT_STARTED` 는 자식 노드 유무와 관계없이 언제나 한 줄로 그린다. 자식 실행이 그 하위
 * 에이전트에서 났는지 짝지을 방법이 없어서다. 자식 실행에는 어느 사건에서 났는지가 적혀 있지 않고,
 * Hermes v0.21.0 은 하위 에이전트 이름조차 보내지 않아 이름으로도 맞출 수 없다. 실제 하위 에이전트가
 * 자식 실행 줄을 남기는 경로가 생기면 이 줄과 한 번 겹쳐 보일 수 있는데, 겹쳐 보이는 쪽이 사건이
 * 통째로 사라지는 쪽보다 낫다. Memory 제안 실행처럼 하위 에이전트가 아닌 자식이 달려도 이 줄이
 * 사라지면 안 되기 때문이다.
 */
export function mergeEvents(events: ExecutionEventView[]): MergedEventRow[] {
  const rows: MergedEventRow[] = [];
  const pendingByTool = new Map<string, Extract<MergedEventRow, { kind: "tool" }>[]>();

  for (const event of events) {
    switch (event.eventType) {
      case "RUN_STARTED":
      case "RUN_COMPLETED":
        break;
      case "RUN_CANCELLED":
        rows.push({ kind: "cancelled", key: event.sequence });
        break;
      case "RUN_FAILED":
        rows.push({ kind: "error", key: event.sequence, detail: event.detail });
        break;
      case "TOOL_STARTED": {
        const row: Extract<MergedEventRow, { kind: "tool" }> = {
          kind: "tool",
          key: event.sequence,
          toolName: event.toolName,
          durationMs: null,
          detail: event.detail,
          finished: false,
        };
        rows.push(row);
        const name = event.toolName ?? "";
        const queue = pendingByTool.get(name) ?? [];
        queue.push(row);
        pendingByTool.set(name, queue);
        break;
      }
      case "TOOL_COMPLETED": {
        const name = event.toolName ?? "";
        const row = pendingByTool.get(name)?.shift();
        if (row) {
          row.durationMs = event.durationMs;
          row.detail = event.detail ?? row.detail;
          row.finished = true;
        } else {
          rows.push({
            kind: "tool",
            key: event.sequence,
            toolName: event.toolName,
            durationMs: event.durationMs,
            detail: event.detail,
            finished: true,
          });
        }
        break;
      }
      case "SUBAGENT_STARTED":
        rows.push({
          kind: "subagent",
          key: event.sequence,
          subagentName: event.subagentName,
          detail: event.detail,
        });
        break;
      case "SUBAGENT_COMPLETED":
        // 시작 사건에서 이미 한 줄로 그렸다.
        break;
      case "PROVIDER_SWITCHED":
        break;
    }
  }
  return rows;
}

/** 노드 하나다. 자기 자신을 자식으로 다시 그린다. */
export function ExecutionNode({ node, depth }: { node: ExecutionTreeNode; depth: number }) {
  const stoppedByScreen = depth >= MAX_DEPTH;
  const rows = mergeEvents(node.events);
  const label = node.agentName ?? node.agentCode ?? `실행 #${node.executionId}`;
  const showsTruncated = node.truncated || (stoppedByScreen && node.children.length > 0);

  return (
    <li className={`min-w-0 ${depth === 0 ? "" : "ml-3"}`} data-testid="execution-node">
      <div className="min-w-0 border-l border-border pl-3">
        <p className="truncate text-sm font-medium">{label}</p>
        <ul className="min-w-0">
          {rows.map((row) => (
            <ExecutionEventRow key={row.key} row={row} />
          ))}
          {!stoppedByScreen
            && node.children.map((child) => (
              <ExecutionNode key={child.executionId} node={child} depth={depth + 1} />
            ))}
          {showsTruncated ? (
            <li className="truncate text-xs text-muted">여기부터 보이지 않는다</li>
          ) : null}
        </ul>
      </div>
    </li>
  );
}
