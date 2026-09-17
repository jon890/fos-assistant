import { ExecutionEventRow, type MergedEventRow } from "./execution-event-row";
import type { ExecutionEventView, ExecutionTreeNode } from "./execution-tree";

/** 서버가 여덟에서 자르지만(`ExecutionTreeService.MAX_DEPTH`), 화면도 스스로 멈춘다. */
const MAX_DEPTH = 8;

/**
 * `TOOL_STARTED` 를 그 뒤의 `TOOL_COMPLETED` 와 짝지어 한 줄로 만든다.
 *
 * <p>짝이 없으면 그대로 한 줄로 남는다. 도구 이름별로 큐를 둬 겹친 호출도 순서대로 짝짓는다.
 * `SUBAGENT_STARTED` 는 이 노드에 자식이 있으면 그 자식 노드로 이미 그리므로 줄로 만들지 않는다.
 */
export function mergeEvents(events: ExecutionEventView[], hasChildren: boolean): MergedEventRow[] {
  const rows: MergedEventRow[] = [];
  const pendingByTool = new Map<string, Extract<MergedEventRow, { kind: "tool" }>[]>();

  for (const event of events) {
    switch (event.eventType) {
      case "RUN_STARTED":
      case "RUN_COMPLETED":
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
        if (!hasChildren) {
          rows.push({
            kind: "subagent",
            key: event.sequence,
            subagentName: event.subagentName,
            detail: event.detail,
          });
        }
        break;
      case "SUBAGENT_COMPLETED":
        // 시작 사건에서 이미 한 줄로 그렸다.
        break;
    }
  }
  return rows;
}

/** 노드 하나다. 자기 자신을 자식으로 다시 그린다. */
export function ExecutionNode({ node, depth }: { node: ExecutionTreeNode; depth: number }) {
  const stoppedByScreen = depth >= MAX_DEPTH;
  const rows = mergeEvents(node.events, node.children.length > 0);
  const label = node.agentName ?? node.agentCode ?? `실행 #${node.executionId}`;
  const showsTruncated = node.truncated || (stoppedByScreen && node.children.length > 0);

  return (
    <li className="min-w-0" style={{ marginLeft: depth === 0 ? 0 : 12 }} data-testid="execution-node">
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
