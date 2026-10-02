"use client";

import { useState } from "react";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { Switch } from "@/components/ui/switch";
import { describeError } from "@/components/error-message";
import {
  GROUP_VISIBILITY,
  type AdminAgent,
  type AgentToolsView,
  type ToolsetView,
} from "@/lib/agent";
import { fetchAgentTools, saveAgentTools } from "@/lib/agent-api";
import { toolsetText } from "@/lib/toolset-label";

type ErrorPayload = {
  code: string;
  message: string;
  missingToolsets?: string[];
};

type Props = {
  code: string;
  initialTools: AgentToolsView;
  admin: boolean;
  visibility: AdminAgent["visibility"] | undefined;
};

/** 관리자 도구를 켤 때 그 도구가 실제로 닿는 대상을 짧게 알린다. */
function confirmationDescription(name: string): string {
  switch (name) {
    case "terminal":
    case "file":
    case "code_execution":
      return "이 도구는 홈서버 파일과 셸에 닿을 수 있어요.";
    case "browser":
      return "이 도구는 웹 브라우저를 조작할 수 있어요.";
    case "session_search":
      return "이 도구는 다른 사람과 나눈 대화까지 찾아 읽을 수 있어요.";
    case "computer_use":
      return "이 도구는 컴퓨터 화면과 입력을 조작할 수 있어요.";
    case "cronjob":
      return "이 도구는 정해 둔 시간에 작업을 실행할 수 있어요.";
    case "image_gen":
      return "이 도구는 이미지를 만들 수 있어요.";
    case "video_gen":
      return "이 도구는 동영상을 만들 수 있어요.";
    case "homeassistant":
      return "이 도구는 집 기기를 제어할 수 있어요.";
    case "spotify":
      return "이 도구는 음악 재생을 제어할 수 있어요.";
    case "discord":
      return "이 도구는 Discord에 메시지를 보낼 수 있어요.";
    default:
      return "이 에이전트가 이 도구로 외부 작업을 할 수 있어요.";
  }
}

/** 에이전트가 다음 실행부터 쓸 도구를 등급별로 보이고 저장한다. */
export function AgentToolsSection({
  code,
  initialTools,
  admin,
  visibility,
}: Props) {
  const [tools, setTools] = useState(initialTools.toolsets);
  const [unclassifiedEnabled, setUnclassifiedEnabled] = useState(
    initialTools.unclassifiedEnabled,
  );
  const [pendingToolName, setPendingToolName] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [missing, setMissing] = useState<string[]>([]);
  const [confirming, setConfirming] = useState<ToolsetView | null>(null);

  async function reload(): Promise<AgentToolsView | null> {
    try {
      const response = await fetchAgentTools(code, admin);
      if (!response.ok) return null;
      const fresh = (await response.json()) as AgentToolsView;
      setTools(fresh.toolsets);
      setUnclassifiedEnabled(fresh.unclassifiedEnabled);
      return fresh;
    } catch {
      return null;
    }
  }

  async function save(next: ToolsetView[], toolName: string) {
    setPendingToolName(toolName);
    setError(null);
    setMissing([]);
    try {
      const response = await saveAgentTools(
        code,
        admin,
        next.filter((tool) => tool.enabled).map((tool) => tool.name),
      );
      if (response.ok) {
        const saved = (await response.json()) as AgentToolsView;
        setTools(saved.toolsets);
        setUnclassifiedEnabled(saved.unclassifiedEnabled);
        return;
      }
      const failure = (await response.json()) as ErrorPayload;
      setError(describeError(failure.code, failure.message));
      if (failure.code === "AGENT_TOOLS_NOT_APPLIED") {
        setMissing(failure.missingToolsets ?? []);
      }
      await reload();
    } catch {
      setError(
        describeError("HERMES_UNAVAILABLE", "도구 설정을 저장하지 못했어요."),
      );
      await reload();
    } finally {
      setPendingToolName(null);
    }
  }

  function toggle(tool: ToolsetView) {
    if (
      !tool.editable ||
      (visibility === GROUP_VISIBILITY && tool.requiresPrivate)
    )
      return;
    if (!tool.enabled && tool.tier === "ADMIN") {
      setConfirming(tool);
      return;
    }
    void save(
      tools.map((current) =>
        current.name === tool.name
          ? { ...current, enabled: !current.enabled }
          : current,
      ),
      tool.name,
    );
  }

  const ownerTools = tools.filter((tool) => tool.tier === "OWNER");
  const adminTools = tools.filter((tool) => tool.tier === "ADMIN");

  function disabledReason(tool: ToolsetView): string | undefined {
    if (visibility === GROUP_VISIBILITY && tool.requiresPrivate)
      return "그룹 공개 에이전트에는 켤 수 없어요";
    if (!tool.editable) return "관리자만 켤 수 있어요";
    return undefined;
  }

  function list(title: string, entries: ToolsetView[]) {
    return (
      <div className="mt-4">
        <h3 className="text-sm font-semibold">{title}</h3>
        <ul className="mt-2 divide-y divide-border rounded-md border border-border">
          {entries.map((tool) => {
            const reason = disabledReason(tool);
            const disabled = pendingToolName !== null || reason !== undefined;
            const text = toolsetText(tool.name, tool);
            return (
              <li
                key={tool.name}
                className="flex items-center justify-between gap-3 p-3"
              >
                <div className="min-w-0">
                  <p className="text-sm font-medium">{text.label}</p>
                  <p className="mt-1 text-xs text-muted-foreground">
                    {text.description}
                  </p>
                  {reason ? (
                    <p className="mt-1 text-xs text-muted-foreground">
                      {reason}
                    </p>
                  ) : null}
                  {missing.includes(tool.name) ? (
                    <p className="mt-1 text-xs text-destructive">
                      이 도구를 켜지 못했어요. 관리자에게 알려 주세요.
                    </p>
                  ) : null}
                </div>
                <Switch
                  checked={tool.enabled}
                  loading={pendingToolName === tool.name}
                  disabled={disabled}
                  title={reason}
                  aria-label={`${text.label} 도구`}
                  onCheckedChange={() => toggle(tool)}
                />
              </li>
            );
          })}
        </ul>
      </div>
    );
  }

  return (
    <section
      aria-label="도구"
      className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4"
    >
      <h2 className="font-semibold">도구</h2>
      <p className="mt-1 text-sm text-muted-foreground">
        저장하면 다음 실행부터 반영돼요.
      </p>
      {error ? (
        <Notice variant="error" role="alert" className="mt-4">
          {error}
        </Notice>
      ) : null}
      {unclassifiedEnabled.length > 0 ? (
        <Notice variant="warning" role="alert" className="mt-4">
          표에 없는 도구가 켜져 있어요. 관리자에게 알려 주세요.
        </Notice>
      ) : null}
      {list("바로 켤 수 있어요", ownerTools)}
      {list("관리자만 켤 수 있어요", adminTools)}
      {confirming ? (
        <AlertDialog
          open
          onOpenChange={(open) => {
            if (!open && pendingToolName === null) setConfirming(null);
          }}
        >
          <AlertDialogContent
            onEscapeKeyDown={(event) => {
              if (pendingToolName !== null) event.preventDefault();
            }}
          >
            <AlertDialogHeader>
              <AlertDialogTitle>
                {toolsetText(confirming.name, confirming).label} 도구 켜기
              </AlertDialogTitle>
              <AlertDialogDescription>
                {confirmationDescription(confirming.name)} 다음 실행부터 이
                에이전트가 쓸 수 있어요.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel asChild>
                <Button variant="outline" disabled={pendingToolName !== null}>
                  취소
                </Button>
              </AlertDialogCancel>
              <Button
                loading={pendingToolName !== null}
                loadingText="저장 중"
                onClick={() => {
                  setConfirming(null);
                  void save(
                    tools.map((tool) =>
                      tool.name === confirming.name
                        ? { ...tool, enabled: true }
                        : tool,
                    ),
                    confirming.name,
                  );
                }}
              >
                켜기
              </Button>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      ) : null}
    </section>
  );
}
