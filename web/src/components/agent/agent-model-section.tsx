"use client";

import { useEffect, useState } from "react";
import { Notice } from "@/components/ui/notice";
import {
  getAgentModelSettings,
  hidesProfileDefault,
  type AgentModelSettings,
} from "@/lib/model-settings";
import { AgentModelDefaultForm } from "./agent-model-default-form";
import { ModelHiddenForm } from "./model-hidden-form";

type Props = { code: string };

type State =
  | { status: "loading" }
  | { status: "failed"; message: string }
  | { status: "loaded"; settings: AgentModelSettings };

/**
 * 관리자가 이 에이전트의 기본 모델과 그룹의 모델 숨김을 정한다.
 *
 * <p>목록은 Hermes 에 물어야 해서 화면이 뜬 뒤에 읽는다. 읽지 못해도 에이전트의 다른 절은 그대로 쓴다.
 * 숨김은 그룹 전체에 걸리지만 숨길 대상의 목록이 에이전트마다 달라 이 절에 함께 둔다.
 */
export function AgentModelSection({ code }: Props) {
  const [state, setState] = useState<State>({ status: "loading" });

  useEffect(() => {
    let active = true;
    void getAgentModelSettings(code).then((result) => {
      if (!active) return;
      setState(
        result.ok
          ? { status: "loaded", settings: result.data }
          : { status: "failed", message: result.message },
      );
    });
    return () => {
      active = false;
    };
  }, [code]);

  return (
    <section
      aria-label="모델"
      data-testid="agent-model-section"
      className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4"
    >
      <h2 className="font-semibold">모델</h2>
      {state.status === "loading" ? (
        <p className="mt-3 text-sm text-muted-foreground">
          모델 목록을 불러오고 있어요.
        </p>
      ) : state.status === "failed" ? (
        <Notice variant="error" role="alert" className="mt-3">
          모델 설정을 불러오지 못했어요. {state.message}
        </Notice>
      ) : (
        <>
          {state.settings.catalogMissing ? (
            <Notice
              variant="warning"
              className="mt-3"
              data-testid="agent-model-catalog-missing"
            >
              모델 목록을 불러오지 못했어요. 지금은 저장된 기본 모델을 비우거나
              숨김을 푸는 것만 할 수 있어요.
            </Notice>
          ) : null}
          {hidesProfileDefault(state.settings) ? (
            <Notice
              variant="warning"
              className="mt-3"
              data-testid="agent-model-profile-default-hidden"
            >
              이 에이전트는 기본 모델을 정하지 않았는데 profile 의 기본 모델이
              숨겨져 있어요. 기본 모델을 정하거나 숨김을 풀기 전에는 모델을
              고르지 않은 대화가 실패해요.
            </Notice>
          ) : null}
          <AgentModelDefaultForm
            code={code}
            settings={state.settings}
            onSaved={(agentDefault) =>
              setState({
                status: "loaded",
                settings: { ...state.settings, agentDefault },
              })
            }
          />
          <ModelHiddenForm
            settings={state.settings}
            onSaved={(entries) =>
              setState({
                status: "loaded",
                settings: { ...state.settings, hidden: { entries } },
              })
            }
          />
        </>
      )}
    </section>
  );
}
