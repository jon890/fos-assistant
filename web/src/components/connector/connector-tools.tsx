import { toolText } from "@/lib/connector-tool-text";
import { Badge } from "@/components/ui/badge";
import {
  toolBlocked,
  toolPolicyLabel,
  toolRiskLabel,
  type ConnectorTool,
} from "@/lib/connection";

/** 실행 방식은 상태라 의미 색으로 그린다. 바로 실행은 success, 실행 전에 묻는 것은 warning 이다. */
function policyVariant(tool: ConnectorTool) {
  if (toolBlocked(tool)) return "destructive";
  return tool.approval === "NONE" ? "success" : "warning";
}

/** 제목을 선언하지 않은 도구에 보이는 이름이다. 실제 도구 이름은 화면에 보이지 않는다. */
const UNNAMED_TOOL_TITLE = "이름 없는 도구";

/** 커넥터가 선언한 도구마다 사람 말 이름과 요약, 위험도와 실행 방식을 보인다. */
export function ConnectorTools({ tools }: { tools: ConnectorTool[] }) {
  if (!Array.isArray(tools) || tools.length === 0) {
    return (
      <p
        className="text-sm text-muted-foreground"
        data-testid="connector-tools-empty"
      >
        이 연결은 조회를 뺀 모든 동작을 실행 전에 물어봐요.
      </p>
    );
  }
  return (
    <ul className="space-y-2" data-testid="connector-tools">
      {tools.map((tool) => (
        <li
          key={tool.name}
          data-testid="connector-tool"
          className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border p-3 text-sm"
        >
          <span className="min-w-0 break-words">
            {tool.title?.trim() || UNNAMED_TOOL_TITLE}
          </span>
          <span className="flex flex-wrap gap-1">
            <Badge variant="outline">{toolRiskLabel(tool.risk)}</Badge>
            <Badge variant={policyVariant(tool)}>{toolPolicyLabel(tool)}</Badge>
          </span>
          {toolText(tool.summary) && (
            <p
              data-testid="connector-tool-caption"
              className="w-full whitespace-pre-wrap break-words text-muted-foreground [overflow-wrap:anywhere]"
            >
              {toolText(tool.summary)}
            </p>
          )}
        </li>
      ))}
    </ul>
  );
}
