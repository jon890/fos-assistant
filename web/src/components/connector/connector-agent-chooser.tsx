"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { CircleCheck, MessageSquare } from "lucide-react";
import { BindConfirm } from "@/components/agent/agent-connections-section";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import type { AgentToolsView, AgentView } from "@/lib/agent";
import { fetchAgentTools } from "@/lib/agent-api";
import {
  bindAgentConnection,
  hasShellOrFileTool,
  type AgentConnectionView,
} from "@/lib/agent-connection";
import { fetchChatAgents } from "@/lib/chat-api";
import type { BoundAgent } from "@/lib/connection";
import {
  agentChoices,
  boundReadiness,
  type AgentChoice,
} from "@/lib/connector-onboarding";
import { withParticle } from "@/lib/korean-particle";

type Agents =
  | { kind: "loading" }
  | { kind: "failed" }
  | { kind: "ready"; agents: AgentView[] };

type Bound = { choice: AgentChoice; view: AgentConnectionView };

/** 고른 뒤 도는 일이다. 도구를 읽어 확인 창이 필요한지 보고, 붙인다. */
type Phase = "check" | "bind";

/** 내 에이전트를 읽는다. 대화 화면과 같은 목록이다. */
async function readAgents(): Promise<Agents> {
  try {
    const response = await fetchChatAgents();
    if (!response.ok) return { kind: "failed" };
    return { kind: "ready", agents: (await response.json()) as AgentView[] };
  } catch {
    return { kind: "failed" };
  }
}

/** 그 에이전트에 셸이나 파일 도구가 켜졌는지 본다. 읽지 못하면 켜졌다고 보고 경고를 띄운다. */
async function shellOrFileEnabled(code: string): Promise<boolean> {
  try {
    const response = await fetchAgentTools(code, false);
    if (!response.ok) return true;
    const tools = (await response.json()) as AgentToolsView;
    return hasShellOrFileTool(
      tools.toolsets.filter((tool) => tool.enabled).map((tool) => tool.name),
    );
  } catch {
    return true;
  }
}

const GROUP_REASON =
  "그룹에 공개한 에이전트라 붙일 수 없어요. 나만 쓰는 에이전트로 바꾸면 붙일 수 있어요.";

/** 후보 한 줄이다. 붙일 수 있는 에이전트만 단추가 있고, 나머지는 까닭을 보인다. */
function ChoiceRow({
  choice,
  busy,
  pending,
  onPick,
}: {
  choice: AgentChoice;
  busy: boolean;
  /** 이 줄에서 도는 일이다. 도구를 읽는 중이면 `check`, 붙이는 중이면 `bind` 다. */
  pending: Phase | null;
  onPick(): void;
}) {
  return (
    <li
      data-testid="agent-choice"
      className="flex flex-wrap items-center justify-between gap-3 p-3"
    >
      <div className="min-w-0 text-sm">
        <p className="font-medium break-all">{choice.name}</p>
        {choice.preferred ? (
          <p className="mt-1 text-xs text-muted-foreground">
            방금 보던 에이전트예요.
          </p>
        ) : null}
        {choice.state === "GROUP" ? (
          <p className="mt-1 text-xs text-muted-foreground">{GROUP_REASON}</p>
        ) : null}
      </div>
      {choice.state === "BINDABLE" ? (
        <Button
          size="sm"
          disabled={busy}
          loading={pending !== null}
          loadingText={pending === "check" ? "확인하는 중" : "붙이는 중"}
          onClick={onPick}
        >
          이 에이전트에서 쓰기
        </Button>
      ) : choice.state === "BOUND" ? (
        <Badge variant="success">이미 쓰고 있어요</Badge>
      ) : null}
    </li>
  );
}

/** 후보 목록이다. 에이전트를 읽는 중이거나 읽지 못했으면 그 사실을, 붙일 수 있는 것이 없으면 만드는 길을 알린다. */
function ChoiceList({
  agents,
  choices,
  bindable,
  pending,
  busy,
  onPick,
}: {
  agents: Agents;
  choices: AgentChoice[];
  bindable: boolean;
  /** 확인 창이 떠 있지 않을 때 고른 뒤 도는 일이다. */
  pending: { code: string; phase: Phase } | null;
  busy: boolean;
  onPick(choice: AgentChoice): void;
}) {
  if (agents.kind === "loading") {
    return (
      <p className="mt-3 text-sm text-muted-foreground">에이전트를 읽는 중…</p>
    );
  }
  if (agents.kind === "failed") {
    return (
      <Notice variant="error" role="alert" className="mt-3">
        에이전트 목록을 읽지 못했어요. 화면을 다시 열어 주세요.
      </Notice>
    );
  }
  return (
    <>
      {choices.length > 0 ? (
        <ul className="mt-3 divide-y divide-border rounded-md border border-border">
          {choices.map((choice) => (
            <ChoiceRow
              key={choice.code}
              choice={choice}
              busy={busy}
              pending={pending?.code === choice.code ? pending.phase : null}
              onPick={() => onPick(choice)}
            />
          ))}
        </ul>
      ) : null}
      {bindable ? null : (
        <p className="mt-3 text-sm text-muted-foreground">
          이 서비스를 붙일 수 있는 내 에이전트가 없어요. 나만 쓰는 에이전트를
          만들면 그 에이전트 화면에서 바로 붙일 수 있어요.
        </p>
      )}
    </>
  );
}

/** 붙인 뒤의 안내다. 그 에이전트와 대화를 바로 시작할 수 있게 한다. */
function BoundResult({
  bound,
  title,
  onClose,
}: {
  bound: Bound;
  title: string;
  onClose(): void;
}) {
  return (
    <div className="space-y-3" data-testid="connector-bound-result">
      <p className="flex items-start gap-2 text-sm">
        <CircleCheck
          aria-hidden="true"
          className="mt-0.5 size-4 shrink-0 text-success"
        />
        <span>
          이제 「{bound.choice.name}」 대화에서 「{title}」 도구를 써요.{" "}
          {boundReadiness(bound.view)}
        </span>
      </p>
      <div className="flex flex-wrap gap-2">
        <Button asChild>
          <Link href={`/?agent=${bound.choice.code}`}>
            <MessageSquare aria-hidden="true" />
            {withParticle(bound.choice.name, "과", "와")} 대화하기
          </Link>
        </Button>
        <Button variant="outline" onClick={onClose}>
          닫기
        </Button>
      </div>
    </div>
  );
}

/**
 * 연결을 마친 직후 같은 자리에서 그 연결을 쓸 에이전트를 고르게 한다.
 *
 * <p>고르면 바로 붙인다. 셸, 파일, 코드 실행 도구가 켜진 에이전트만 에이전트 화면과 같은 확인 창을 거친다. 그런
 * 도구가 연결의 비밀값에 닿거나 승인 없이 서비스를 부를 수 있기 때문이다(ADR-086). 붙이고 나면 그 에이전트와
 * 대화하기로 잇는다. 붙일 수 있는 에이전트가 없으면 에이전트를 만드는 길을 안내한다.
 */
export function ConnectorAgentChooser({
  connectorId,
  title,
  bindings,
  justConnected,
  preferredAgent,
  onBindingsChanged,
  onClose,
}: {
  connectorId: string;
  title: string;
  bindings: BoundAgent[];
  /** 방금 연결을 마쳤다. 제목이 그 사실을 알리고 이 영역에 초점을 옮긴다. */
  justConnected: boolean;
  /** 에이전트 화면에서 이 연결을 하러 왔으면 그 에이전트 번호다. */
  preferredAgent: string | null;
  /** 붙였거나 붙이다 실패했다. 실패해도 서버에 바인딩이 남았을 수 있어 연결 화면이 붙인 에이전트 목록을 다시 읽는다. */
  onBindingsChanged(): void;
  onClose(): void;
}) {
  const [agents, setAgents] = useState<Agents>({ kind: "loading" });
  const [pending, setPending] = useState<{
    code: string;
    phase: Phase;
  } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<AgentChoice | null>(null);
  const [bound, setBound] = useState<Bound | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    void readAgents().then(setAgents);
  }, []);

  useEffect(() => {
    if (justConnected) headingRef.current?.focus();
  }, [justConnected]);

  async function bind(choice: AgentChoice) {
    setPending({ code: choice.code, phase: "bind" });
    setError(null);
    const result = await bindAgentConnection(choice.code, connectorId);
    setPending(null);
    onBindingsChanged();
    if (!result.ok) return setError(result.message);
    setConfirming(null);
    setBound({ choice, view: result.data });
  }

  async function pick(choice: AgentChoice) {
    if (pending !== null) return;
    setPending({ code: choice.code, phase: "check" });
    setError(null);
    const risky = await shellOrFileEnabled(choice.code);
    if (risky) {
      setPending(null);
      setConfirming(choice);
      return;
    }
    await bind(choice);
  }

  function cancelConfirm() {
    if (pending !== null) return;
    setConfirming(null);
    setError(null);
  }

  const choices =
    agents.kind === "ready"
      ? agentChoices(agents.agents, bindings, preferredAgent)
      : [];
  const bindable = choices.some((choice) => choice.state === "BINDABLE");

  return (
    <section
      aria-labelledby="connector-agent-chooser-heading"
      data-testid="connector-agent-chooser"
      className="mb-4 rounded-md border border-border bg-card p-4"
    >
      <h2
        id="connector-agent-chooser-heading"
        ref={headingRef}
        tabIndex={-1}
        className="font-semibold outline-none"
      >
        {bound
          ? `「${bound.choice.name}」에 붙였어요`
          : justConnected
            ? `${title} 연결이 끝났어요`
            : "이 서비스를 쓸 에이전트"}
      </h2>
      {bound ? (
        <div className="mt-3">
          <BoundResult bound={bound} title={title} onClose={onClose} />
        </div>
      ) : (
        <>
          <p className="mt-1 text-sm text-muted-foreground">
            어느 에이전트에서 쓸까요? 고른 에이전트가 이 서비스의 도구를 직접
            써요.
          </p>
          <ChoiceList
            agents={agents}
            choices={choices}
            bindable={bindable}
            pending={confirming === null ? pending : null}
            busy={pending !== null}
            onPick={(choice) => void pick(choice)}
          />
          {error && confirming === null ? (
            <Notice variant="error" role="alert" className="mt-3">
              {error}
            </Notice>
          ) : null}
          <div className="mt-3 flex flex-wrap gap-2">
            {agents.kind === "ready" && !bindable ? (
              <Button asChild>
                <Link href="/agents?new=1">에이전트 만들기</Link>
              </Button>
            ) : null}
            <Button
              variant="outline"
              disabled={pending !== null}
              onClick={onClose}
            >
              나중에
            </Button>
          </div>
        </>
      )}
      {confirming ? (
        <BindConfirm
          title={title}
          busy={pending !== null}
          error={error}
          shellOrFile
          onCancel={cancelConfirm}
          onConfirm={() => void bind(confirming)}
        />
      ) : null}
    </section>
  );
}
