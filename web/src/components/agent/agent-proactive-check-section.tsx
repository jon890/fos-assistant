"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { describeError } from "@/components/error-message";
import { Button } from "@/components/ui/button";
import { useConversations } from "@/components/shell/conversations-provider";
import { Notice } from "@/components/ui/notice";
import { formatWhen } from "@/lib/format";
import {
  describeBlocker,
  describeLastCheck,
  fetchProactiveCheckStatus,
  START_FAILURES,
  startProactiveCheck,
  type ProactiveCheckStatus,
} from "@/lib/proactive-check";

type Props = {
  code: string;
  initialStatus: ProactiveCheckStatus;
};

type ErrorPayload = { code?: string; message?: string };

/**
 * 먼저 살펴보기를 시작하는 절이다. 할 수 없을 때는 까닭마다 할 일을 보인다.
 *
 * <p>시작이 `PROACTIVE_CHECK_UNAVAILABLE` 로 거절되면 응답에 까닭이 없으므로 상태를 다시 읽어 그린다.
 */
export function AgentProactiveCheckSection({ code, initialStatus }: Props) {
  const router = useRouter();
  const { refresh } = useConversations();
  const [status, setStatus] = useState(initialStatus);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function reload(): Promise<string | null> {
    try {
      const response = await fetchProactiveCheckStatus(code);
      if (!response.ok) {
        const payload = (await response
          .json()
          .catch(() => ({}))) as ErrorPayload;
        return describeError(
          payload.code ?? "INTERNAL_ERROR",
          payload.message ?? "상태를 읽지 못했어요.",
        );
      }
      setStatus((await response.json()) as ProactiveCheckStatus);
      return null;
    } catch {
      return describeError("HERMES_UNAVAILABLE", "연결할 수 없어요.");
    }
  }

  async function start() {
    setStarting(true);
    setError(null);
    try {
      const response = await startProactiveCheck(code);
      if (response.ok) {
        const started = (await response.json()) as { conversationId: string };
        // 점검 대화가 새로 만들어졌을 수 있다. 사이드바 목록에 그 줄이 보이도록 목록을 다시 읽고 옮긴다.
        await refresh();
        // 이동이 끝나기 전에 다시 누르지 않게 단추를 잠근 채 둔다.
        router.push(`/chat/${started.conversationId}`);
        return;
      }
      const payload = (await response.json().catch(() => ({}))) as ErrorPayload;
      if (payload.code === "PROACTIVE_CHECK_UNAVAILABLE") {
        // 까닭은 응답에 없다. 읽은 상태가 그대로 할 수 있다는 뜻이면 거절 문구를 보인다.
        const failure = await reload();
        setError(failure ?? describeError("PROACTIVE_CHECK_UNAVAILABLE", ""));
      } else {
        setError(
          START_FAILURES[payload.code ?? ""] ??
            describeError(
              payload.code ?? "INTERNAL_ERROR",
              payload.message ?? "요청을 처리하지 못했어요.",
            ),
        );
      }
    } catch {
      setError(describeError("HERMES_UNAVAILABLE", "연결할 수 없어요."));
    }
    setStarting(false);
  }

  const lastCheck = status.lastCheck;
  return (
    <section
      aria-label="먼저 살펴보기"
      className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4"
    >
      <h2 className="font-semibold">먼저 살펴보기</h2>
      <p className="mt-1 text-sm text-muted-foreground">
        묻지 않아도 에이전트가 새로 알릴 것이 있는지 살펴봐요. 읽기만 하고
        아무것도 바꾸지 않아요.
      </p>
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <Button
          disabled={!status.available}
          loading={starting}
          loadingText="시작하는 중"
          onClick={() => void start()}
        >
          지금 살펴보기
        </Button>
        {status.conversationId ? (
          <Link
            prefetch={false}
            href={`/chat/${status.conversationId}`}
            className="text-sm underline underline-offset-4"
          >
            점검 대화 열기
          </Link>
        ) : null}
      </div>
      {error ? (
        <Notice variant="error" role="alert" className="mt-3">
          {error}
        </Notice>
      ) : null}
      {status.blockers.length > 0 ? (
        <ul className="mt-3 flex flex-col gap-2">
          {status.blockers.map((blocker) => (
            <li key={blocker.code}>
              <Notice variant="info">{describeBlocker(blocker)}</Notice>
            </li>
          ))}
        </ul>
      ) : null}
      {lastCheck ? (
        <p className="mt-3 text-sm text-muted-foreground">
          마지막 살펴보기{" "}
          {formatWhen(lastCheck.finishedAt ?? lastCheck.startedAt)},{" "}
          {describeLastCheck(lastCheck)}
        </p>
      ) : null}
    </section>
  );
}
