import { toolText } from "@/lib/connector-tool-text";
import { Badge } from "@/components/ui/badge";
import { Notice } from "@/components/ui/notice";
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

/** 플러그인이 선언한 도구마다 사람 말 이름과 요약, 위험도와 실행 방식을 보인다. */
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

/** 표준 플러그인 카드와 연결 화면이 함께 보이는 안내다. */
export const STANDARD_PLUGIN_NOTICE = "모든 도구를 쓸 때마다 확인해요.";

/**
 * `assistant.json` 이 없는 표준 플러그인의 「할 수 있는 일」 이다. 선언한 도구가 없어 마지막 연결 확인에서 받은 도구 수만 보인다.
 * 읽기 표시가 있는 도구는 한 번 승인한 뒤 계속 허락할 수 있다.
 */
export function StandardPluginTools({
  listedTools,
  readOnlyTools,
}: {
  listedTools: number | null;
  readOnlyTools: number | null;
}) {
  return (
    <div className="space-y-1 text-sm" data-testid="standard-plugin-tools">
      <p>{STANDARD_PLUGIN_NOTICE}</p>
      {listedTools !== null ? (
        <p
          className="text-muted-foreground"
          data-testid="standard-plugin-tool-count"
        >
          마지막 확인에서 도구 {listedTools}개를 받았어요.
          {readOnlyTools
            ? ` 그 가운데 읽기만 하는 도구 ${readOnlyTools}개는 한 번 승인한 뒤 계속 허락할 수 있어요.`
            : ""}
        </p>
      ) : null}
    </div>
  );
}

/** 연결 화면의 「할 수 있는 일」 절이다. 서비스가 알려 주지 않은 도구는 쓰지 않는다고 알린다. */
export function ConnectorToolsSection({
  tools,
  undeclaredTools,
  standard,
}: {
  tools: ConnectorTool[];
  undeclaredTools: number;
  /** 표준 플러그인이면 마지막 확인에서 받은 도구 수다. 아니면 없다. */
  standard?: { listedTools: number | null; readOnlyTools: number | null };
}) {
  return (
    <section className="space-y-2">
      <h2 className="text-sm font-semibold">할 수 있는 일</h2>
      {standard ? (
        <StandardPluginTools {...standard} />
      ) : (
        <ConnectorTools tools={tools} />
      )}
      {undeclaredTools > 0 ? (
        <Notice
          variant="info"
          role="status"
          data-testid="connection-undeclared"
        >
          이 서비스가 알려 주지 않은 도구 {undeclaredTools}개는 쓰지 않아요.
        </Notice>
      ) : null}
    </section>
  );
}
