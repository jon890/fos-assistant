"use client";

import { useState, type ComponentProps } from "react";
import { ConnectorAgentChooser } from "@/components/connector/connector-agent-chooser";
import { shouldOfferSpecialist } from "@/lib/specialist-agent";
import { SpecialistAgentOffer } from "./specialist-agent-offer";

/**
 * 연결을 마친 화면 맨 위의 영역이다. 처음 연결을 마쳤으면 전문 에이전트 제안을, 아니면 지금의 에이전트 고르기를 그린다
 * (docs/features/agent-team.md 「전문 에이전트 만들기」). 제안을 거절하면 고르기로 가고, 만들면 바인딩이 생겨도
 * 만든 결과를 그대로 보인다.
 */
export function ConnectorOnboarding({
  onCreated,
  ...chooser
}: ComponentProps<typeof ConnectorAgentChooser> & {
  /** 전문 에이전트를 만들었다. 바인딩이 생겨도 이 영역이 닫히지 않게 부르는 쪽이 연 채로 둔다. */
  onCreated(): void;
}) {
  const [offer, setOffer] = useState<"auto" | "declined" | "created">("auto");
  const offered =
    offer === "created" ||
    (offer === "auto" &&
      shouldOfferSpecialist({
        justConnected: chooser.justConnected,
        bindings: chooser.bindings,
        preferredAgent: chooser.preferredAgent,
      }));
  if (!offered) return <ConnectorAgentChooser {...chooser} />;
  return (
    <SpecialistAgentOffer
      connectorId={chooser.connectorId}
      title={chooser.title}
      bindings={chooser.bindings}
      onDecline={() => setOffer("declined")}
      onCreated={() => {
        setOffer("created");
        onCreated();
        chooser.onBindingsChanged();
      }}
      onClose={chooser.onClose}
    />
  );
}
