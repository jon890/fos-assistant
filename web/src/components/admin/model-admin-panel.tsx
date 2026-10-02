"use client";

import { useEffect, useId, useState } from "react";
import { GroupTierSettings } from "@/components/admin/group-tier-settings";
import { ModelHiddenForm } from "@/components/agent/model-hidden-form";
import { NativeSelect } from "@/components/ui/native-select";
import { Notice } from "@/components/ui/notice";
import {
  getAgentModelSettings,
  type AgentModelSettings,
} from "@/lib/model-settings";

type Props = { agents: { code: string; name: string }[] };

type Settings =
  | { status: "failed"; code: string; message: string }
  | { status: "loaded"; code: string; settings: AgentModelSettings };

/**
 * 그룹 전체에 걸리는 모델 설정이다. 에이전트를 고르는 칸은 「모델 숨김」 절 안에 있고 숨길 모델의 목록만 정한다.
 * 목록이 에이전트의 profile 마다 다르기 때문이다. 그룹 모델 설정은 첫 에이전트로 읽고, 고른 에이전트가
 * 바뀌어도 다시 그리지 않아 저장하지 않은 입력이 남는다.
 */
export function ModelAdminPanel({ agents }: Props) {
  const selectId = useId();
  const tierAgentCode = agents[0]?.code ?? "";
  const [code, setCode] = useState(tierAgentCode);
  const [settings, setSettings] = useState<Settings | null>(null);

  useEffect(() => {
    if (!code) return;
    let active = true;
    void getAgentModelSettings(code).then((result) => {
      if (!active) return;
      setSettings(
        result.ok
          ? { status: "loaded", code, settings: result.data }
          : { status: "failed", code, message: result.message },
      );
    });
    return () => {
      active = false;
    };
  }, [code]);

  return (
    <div className="mx-auto w-full max-w-2xl">
      <h1 className="mb-6 text-xl font-semibold">모델</h1>
      {agents.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          먼저 에이전트를 등록해 주세요.
        </p>
      ) : (
        <div className="grid gap-6">
          <p className="text-sm text-muted-foreground">
            그룹 모델 설정과 모델 숨김은 그룹 전체에 걸려요.
          </p>
          <section
            aria-label="그룹 모델 설정"
            className="rounded-md border border-border p-4"
          >
            <h2 className="font-semibold">그룹 모델 설정</h2>
            <GroupTierSettings agentCode={tierAgentCode} />
          </section>
          <section
            aria-label="모델 숨김"
            className="grid gap-4 rounded-md border border-border p-4"
          >
            <h2 className="font-semibold">모델 숨김</h2>
            <label htmlFor={selectId} className="grid gap-1 text-sm">
              숨길 모델의 목록을 읽을 에이전트
              <NativeSelect
                id={selectId}
                value={code}
                onChange={(event) => setCode(event.target.value)}
              >
                {agents.map((agent) => (
                  <option key={agent.code} value={agent.code}>
                    {agent.name}
                  </option>
                ))}
              </NativeSelect>
            </label>
            <p className="text-sm text-muted-foreground">
              고른 에이전트는 숨길 모델의 목록만 정해요.
            </p>
            {settings === null || settings.code !== code ? (
              <p className="text-sm text-muted-foreground">
                모델 목록을 불러오고 있어요.
              </p>
            ) : settings.status === "failed" ? (
              <Notice variant="error" role="alert">
                모델 설정을 불러오지 못했어요. {settings.message}
              </Notice>
            ) : (
              <ModelHiddenForm
                key={code}
                showTitle={false}
                settings={settings.settings}
                onSaved={(entries) =>
                  setSettings({
                    status: "loaded",
                    code,
                    settings: { ...settings.settings, hidden: { entries } },
                  })
                }
              />
            )}
          </section>
        </div>
      )}
    </div>
  );
}
