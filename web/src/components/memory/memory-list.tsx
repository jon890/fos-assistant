"use client";

import { useEffect, useState } from "react";
import { fetchMemories } from "@/lib/memory-api";
import { MEMORY_IMPORTED_EVENT } from "@/lib/memory-import";
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
  after,
}: {
  initialMemories: Memory[];
  isAdmin: boolean;
  currentUserId?: number;
  /** 기억 절 뒤에 이어 그릴 절이다. 문서와 외부 서비스 연결이 들어온다. */
  after?: React.ReactNode;
}) {
  const [memories, setMemories] = useState(initialMemories);
  /** 화면을 처음 그릴 때 있던 기억들이다. 여기 없는 줄만 새 줄로 보고 등장 움직임을 준다 */
  const [initialIds] = useState(
    () => new Set(initialMemories.map((memory) => memory.id)),
  );
  async function reload() {
    const response = await fetchMemories();
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
  // 가져오기 절은 서버가 그린 자식이라 이 목록의 상태를 모른다. 가져오기가 끝났다는 알림을 받아 다시 읽는다.
  useEffect(() => {
    window.addEventListener(MEMORY_IMPORTED_EVENT, reload);
    return () => window.removeEventListener(MEMORY_IMPORTED_EVENT, reload);
  });
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
              entering={!initialIds.has(memory.id)}
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
              entering={!initialIds.has(memory.id)}
              onChanged={reload}
            />
          ))
        ) : (
          <p className="text-sm text-muted-foreground">
            아직 나에 대해 아는 기억이 없어요.
          </p>
        )}
      </Section>
      {after}
    </div>
  );
}
