"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { CircleCheck, MessageSquare } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { BoundAgent } from "@/lib/connection";
import { boundReadiness } from "@/lib/connector-onboarding";
import { withParticle } from "@/lib/korean-particle";
import {
  createdMessage,
  type SpecialistAgentResult,
} from "@/lib/specialist-agent";
import { SpecialistAgentForm } from "./specialist-agent-form";

/**
 * 연결을 처음 마친 화면의 제안이다(docs/features/agent-team/spec.md 의 REQ5).
 * 받으면 같은 자리에서 만들기 화면을 열고, 거절하면 부르는 쪽이 지금의 에이전트 고르기를 연다.
 */
export function SpecialistAgentOffer({
  connectorId,
  title,
  bindings,
  onDecline,
  onCreated,
  onClose,
}: {
  connectorId: string;
  title: string;
  /** 그 연결이 붙은 에이전트들이다. 외부 서비스 연결 화면이 반영을 기다리며 다시 읽은 상태다. */
  bindings: readonly BoundAgent[];
  /** 「고른 에이전트에 붙이기」 를 눌렀다. */
  onDecline(): void;
  /** 만들었다. 외부 서비스 연결 화면이 붙인 에이전트 목록을 다시 읽는다. */
  onCreated(): void;
  onClose(): void;
}) {
  const [step, setStep] = useState<"offer" | "form">("offer");
  const [created, setCreated] = useState<SpecialistAgentResult | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    headingRef.current?.focus();
  }, [step, created]);

  return (
    <section
      aria-labelledby="specialist-agent-offer-heading"
      data-testid="specialist-agent-offer"
      className="mb-4 rounded-md border border-border bg-card p-4"
    >
      <h2
        id="specialist-agent-offer-heading"
        ref={headingRef}
        tabIndex={-1}
        className="font-semibold outline-none"
      >
        {created
          ? "전문 에이전트를 만들었어요"
          : step === "form"
            ? "전문 에이전트 만들기"
            : `${title} 연결이 끝났어요`}
      </h2>
      {created ? (
        <div className="mt-3 space-y-3">
          <p className="flex items-start gap-2 text-sm">
            <CircleCheck
              aria-hidden="true"
              className="mt-0.5 size-4 shrink-0 text-success"
            />
            <span>
              {createdMessage(created.agentName, title)}{" "}
              {boundReadiness(
                // 만든 응답은 그때의 상태다. 서버가 반영을 마쳐 목록이 READY 로 바뀌면 그 상태를 따른다.
                bindings.find(
                  (binding) => binding.agentCode === created.agentCode,
                ) ?? created.connection,
              )}
            </span>
          </p>
          <div className="flex flex-wrap gap-2">
            <Button asChild>
              <Link href={`/?agent=${created.agentCode}`}>
                <MessageSquare aria-hidden="true" />
                {withParticle(created.agentName, "과", "와")} 대화하기
              </Link>
            </Button>
            <Button variant="outline" onClick={onClose}>
              닫기
            </Button>
          </div>
        </div>
      ) : step === "form" ? (
        <div className="mt-3">
          <SpecialistAgentForm
            connectorId={connectorId}
            title={title}
            bindings={[]}
            singleBinding={false}
            cancelLabel="고른 에이전트에 붙이기"
            onCancel={onDecline}
            onCreated={(result) => {
              setCreated(result);
              onCreated();
            }}
          />
        </div>
      ) : (
        <>
          <p className="mt-1 text-sm text-muted-foreground">
            이 서비스만 맡는 전문 에이전트를 만들까요? 도구가 적은 에이전트가 이
            서비스 일을 더 잘 찾아 써요. 비서는 그 에이전트에게 맡겨요.
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            <Button onClick={() => setStep("form")}>만들기</Button>
            <Button variant="outline" onClick={onDecline}>
              고른 에이전트에 붙이기
            </Button>
          </div>
        </>
      )}
    </section>
  );
}
