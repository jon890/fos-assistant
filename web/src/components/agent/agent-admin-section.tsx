"use client";

import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Switch } from "@/components/ui/switch";
import { describeAdminError } from "@/components/error-message";
import type { AdminAgent } from "@/lib/agent";
import { updateAdminAgent } from "@/lib/agent-api";

type AgentAction = "enabled" | "address" | "writes";

/** 「먼저 살펴보기에 쓰기 도구 허용」 을 켰을 때 보이는 위험 안내다. 글은 web/docs/prd.md 「에이전트 화면」 의 관리 절과 같다. */
const WRITES_RISK =
  "켜면 먼저 살펴보기가 셸, 파일, 브라우저, 외부 메시지 같은 관리자 도구를 써서, 웹 결과 속 글이 명령 실행이나 외부 연락으로 이어질 수 있어요";

type Props = {
  initialAgent: AdminAgent;
  /**
   * 지금 공개 범위다. 「공개와 삭제」 절이 바꾸므로 이 절이 처음 받은 값을 쓰지 않고,
   * 저장할 때마다 이 값을 그대로 보내 그 절의 변경을 되돌리지 않는다.
   */
  visibility: AdminAgent["visibility"];
};

async function payload<T>(response: Response): Promise<T> {
  return (await response.json()) as T;
}

/** 관리자가 에이전트 하나의 실행과 연결 설정을 고친다. */
export function AgentAdminSection({ initialAgent, visibility }: Props) {
  const [agent, setAgent] = useState(initialAgent);
  const [pending, setPending] = useState<AgentAction | null>(null);
  const [error, setError] = useState<string | null>(null);
  const busy = pending !== null;

  async function update(
    changes: Partial<
      Pick<AdminAgent, "enabled" | "apiBaseUrl" | "proactiveCheckWritesAllowed">
    >,
    action: AgentAction,
  ): Promise<void> {
    setPending(action);
    setError(null);
    try {
      const response = await updateAdminAgent(agent.code, {
        enabled: changes.enabled ?? agent.enabled,
        visibility,
        // 공개 범위와 주인은 바꾸지 않는다. 주인을 비워 보내 기존 주인을 그대로 둔다.
        ownerEmail: null,
        apiBaseUrl: changes.apiBaseUrl,
        // 비워 보내면 서버가 지금 값을 둔다. 이 칸을 바꿀 때만 싣는다.
        proactiveCheckWritesAllowed: changes.proactiveCheckWritesAllowed,
      });
      if (!response.ok) {
        const result = await payload<{ code: string; message: string }>(
          response,
        );
        setError(describeAdminError(result.code, result.message));
        return;
      }
      setAgent(await payload<AdminAgent>(response));
    } finally {
      setPending(null);
    }
  }

  async function saveAddress(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await update(
      { apiBaseUrl: String(form.get("apiBaseUrl") ?? "") },
      "address",
    );
  }

  return (
    <section
      aria-label="관리"
      className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="font-semibold">관리</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            사용 여부와 연결 설정을 고칠 수 있어요.
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Badge variant={agent.enabled ? "success" : "warning"}>
            {agent.enabled ? "사용 중" : "꺼짐"}
          </Badge>
        </div>
      </div>
      {error ? (
        <Notice variant="error" role="alert" className="mt-4">
          {error}
        </Notice>
      ) : null}
      <form onSubmit={(event) => void saveAddress(event)} className="mt-4">
        <div className="grid gap-1.5">
          <Label htmlFor="agent-api-base-url">에이전트 연결 주소</Label>
          <Input
            id="agent-api-base-url"
            name="apiBaseUrl"
            key={agent.apiBaseUrl}
            defaultValue={agent.apiBaseUrl}
            aria-label={`${agent.name} 에이전트 연결 주소`}
          />
        </div>
        <p className="mt-2 text-xs text-muted-foreground">
          저장하기 전에 이 주소에 연결되는지 확인해 주세요.
        </p>
        <Button
          type="submit"
          size="sm"
          variant="outline"
          disabled={busy}
          loading={pending === "address"}
          loadingText="확인하는 중"
          className="mt-2"
        >
          주소 저장
        </Button>
      </form>
      {agent.connectorManaged ? null : (
        <div className="mt-4">
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="text-sm font-medium">
                먼저 살펴보기에 쓰기 도구 허용
              </p>
              <p className="mt-1 text-xs text-muted-foreground">
                연결한 서비스에 쓰는 일은 켜도 사용자 승인을 기다려요.
              </p>
            </div>
            <Switch
              checked={agent.proactiveCheckWritesAllowed}
              loading={pending === "writes"}
              disabled={busy}
              aria-label="먼저 살펴보기에 쓰기 도구 허용"
              onCheckedChange={(next) =>
                void update({ proactiveCheckWritesAllowed: next }, "writes")
              }
            />
          </div>
          {agent.proactiveCheckWritesAllowed ? (
            <Notice variant="warning" className="mt-2">
              {WRITES_RISK}
            </Notice>
          ) : null}
        </div>
      )}
      <div className="mt-4 flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="ghost"
          disabled={busy}
          loading={pending === "enabled"}
          loadingText={agent.enabled ? "중지하는 중" : "켜는 중"}
          onClick={() => void update({ enabled: !agent.enabled }, "enabled")}
        >
          {agent.enabled ? "사용 중지" : "다시 사용"}
        </Button>
      </div>
    </section>
  );
}
