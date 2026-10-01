"use client";

import { useState } from "react";
import { MemoryForm } from "./memory-form";
import { MemoryItem } from "./memory-item";
import { MemoryProposal } from "./memory-proposal";

export type Memory = {
  id: number;
  scope: "USER" | "GROUP";
  ownerUserId: number | null;
  title: string;
  content: string;
  alwaysInject: boolean;
  status: "PROPOSED" | "ACCEPTED" | "REJECTED";
  omittedFromContext?: boolean;
};

function Section({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section className="mb-8">
      <h2 className="mb-3 text-lg font-semibold">{title}</h2>
      <div className="grid gap-3">{children}</div>
    </section>
  );
}

export function MemoryList({
  initialMemories,
  isAdmin,
  currentUserId,
}: {
  initialMemories: Memory[];
  isAdmin: boolean;
  currentUserId?: number;
}) {
  const [memories, setMemories] = useState(initialMemories);
  async function reload() {
    const response = await fetch("/api/memories", { cache: "no-store" });
    if (response.ok) {
      const next = (await response.json()) as Memory[];
      setMemories(next);
      window.dispatchEvent(
        new CustomEvent("memory-proposal-count", {
          detail: next.filter((memory) => memory.status === "PROPOSED").length,
        }),
      );
    }
  }
  const proposals = memories.filter((memory) => memory.status === "PROPOSED");
  const group = memories.filter(
    (memory) => memory.status === "ACCEPTED" && memory.scope === "GROUP",
  );
  const personal = memories.filter(
    (memory) => memory.status === "ACCEPTED" && memory.scope === "USER",
  );
  return (
    <div className="mx-auto w-full max-w-4xl">
      <h1 className="mb-2 text-xl font-semibold">기억</h1>
      <p className="mb-6 max-w-2xl text-sm leading-6 text-muted-foreground">
        받아들인 기억만 다음 대화에 사용해요.
      </p>
      <MemoryForm isAdmin={isAdmin} onCreated={reload} />
      {proposals.length > 0 ? (
        <Section title="검토할 기억">
          {proposals.map((memory) => (
            <MemoryProposal
              key={memory.id}
              memory={memory}
              onChanged={reload}
            />
          ))}
        </Section>
      ) : null}
      <Section title="그룹이 함께 아는 것">
        {group.length > 0 ? (
          group.map((memory) => (
            <MemoryItem
              key={memory.id}
              memory={memory}
              canEdit={isAdmin}
              onChanged={reload}
            />
          ))
        ) : (
          <p className="text-sm text-muted-foreground">
            아직 그룹이 함께 아는 기억이 없어요.
          </p>
        )}
      </Section>
      <Section title="나에 대해 아는 것">
        {personal.length > 0 ? (
          personal.map((memory) => (
            <MemoryItem
              key={memory.id}
              memory={memory}
              canEdit={memory.ownerUserId === currentUserId}
              onChanged={reload}
            />
          ))
        ) : (
          <p className="text-sm text-muted-foreground">
            아직 나에 대해 아는 기억이 없어요.
          </p>
        )}
      </Section>
    </div>
  );
}
