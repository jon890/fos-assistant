"use client";

import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { fetchMemories } from "@/lib/memory-api";
import { MEMORY_MANUAL_CREATE } from "@/lib/memory-features";
import type { MemorySourceFields } from "@/lib/memory-source";
import {
  MEMORY_FILTERS,
  MemoryFilterBar,
  matchesFilter,
  type MemoryFilter,
} from "./memory-filter";
import { MemoryForm } from "./memory-form";
import { MemoryItem } from "./memory-item";
import { MemoryProposal } from "./memory-proposal";
import { MemoryTabs, type MemoryTab } from "./memory-tabs";

export type Memory = MemorySourceFields & {
  id: number;
  scope: "USER" | "GROUP";
  ownerUserId: number | null;
  title: string;
  /** 민감한 기억은 빈 글이다. */
  content: string;
  alwaysInject: boolean;
  status: "PROPOSED" | "ACCEPTED" | "REJECTED";
  sensitive?: boolean;
  omittedFromContext?: boolean;
  createdAt?: string;
  updatedAt?: string;
};

/** 바뀐 시각이 최근인 것부터 둔다. 시각이 없으면 나중에 만든 것부터 둔다. */
function byRecent(left: Memory, right: Memory): number {
  const a = left.updatedAt ?? left.createdAt ?? "";
  const b = right.updatedAt ?? right.createdAt ?? "";
  if (a !== b) return a < b ? 1 : -1;
  return right.id - left.id;
}

export function MemoryList({
  initialMemories,
  isAdmin,
  currentUserId,
  readAt,
  documents,
  advanced,
}: {
  initialMemories: Memory[];
  isAdmin: boolean;
  currentUserId?: number;
  /** 서버가 목록을 읽은 시각이다. 서버와 브라우저가 같은 상대 시각을 그리도록 지금 시각 대신 쓴다 */
  readAt: string;
  /** 「문서」 탭에 그릴 절이다. 문서를 읽지 못했으면 비어 있고 탭을 보이지 않는다. */
  documents?: React.ReactNode;
  /** 목록 아래 접어 두는 절이다. 문서 읽기 토큰이 들어온다. */
  advanced?: React.ReactNode;
}) {
  const [memories, setMemories] = useState(initialMemories);
  const [tab, setTab] = useState<MemoryTab>("memory");
  const [filter, setFilter] = useState<MemoryFilter>("ALL");
  /** 펼친 줄이다. 한 번에 하나만 펼친다. */
  const [openId, setOpenId] = useState<number | null>(null);
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
  function toggle(id: number) {
    setOpenId((current) => (current === id ? null : id));
  }
  const proposals = memories
    .filter((memory) => memory.status === "PROPOSED")
    .sort(byRecent);
  const accepted = memories
    .filter((memory) => memory.status === "ACCEPTED")
    .filter((memory) => matchesFilter(memory, filter))
    .sort(byRecent);
  const current = MEMORY_FILTERS.find((option) => option.key === filter)!;

  const memoryPanel = (
    <>
      {MEMORY_MANUAL_CREATE ? (
        <MemoryForm isAdmin={isAdmin} onCreated={reload} />
      ) : null}
      {proposals.length > 0 ? (
        <section className="mb-8" aria-labelledby="memory-review-heading">
          <div className="mb-1 flex items-center gap-2">
            <h2 id="memory-review-heading" className="text-lg font-semibold">
              검토할 기억
            </h2>
            <Badge variant="info">{proposals.length}</Badge>
          </div>
          <p className="mb-3 text-sm text-muted-foreground">
            에이전트가 기억해도 될지 물어본 것이에요. 눌러서 내용을 보고
            받아들이거나 거절해 주세요.
          </p>
          <ul className="grid gap-2">
            {proposals.map((memory) => (
              <MemoryProposal
                key={memory.id}
                memory={memory}
                open={openId === memory.id}
                readAt={readAt}
                onToggle={() => toggle(memory.id)}
                onChanged={reload}
              />
            ))}
          </ul>
        </section>
      ) : null}
      <section aria-labelledby="memory-accepted-heading">
        <h2 id="memory-accepted-heading" className="mb-3 text-lg font-semibold">
          알고 있는 것
        </h2>
        <MemoryFilterBar value={filter} onChange={setFilter} />
        {accepted.length > 0 ? (
          <ul className="grid gap-2" aria-label="기억 목록">
            {accepted.map((memory) => (
              <MemoryItem
                key={memory.id}
                memory={memory}
                canEdit={
                  memory.scope === "GROUP"
                    ? isAdmin
                    : memory.ownerUserId === currentUserId
                }
                open={openId === memory.id}
                readAt={readAt}
                entering={!initialIds.has(memory.id)}
                onToggle={() => toggle(memory.id)}
                onChanged={reload}
              />
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted-foreground">{current.empty}</p>
        )}
      </section>
    </>
  );

  return (
    <div className="mx-auto w-full max-w-4xl">
      <h1 className="mb-2 text-xl font-semibold">기억</h1>
      <p className="mb-6 max-w-2xl text-sm leading-6 text-muted-foreground">
        에이전트가 대화하며 남긴 기억이에요. 받아들인 기억만 다음 대화에
        사용해요. 눌러서 내용을 보고 고치거나 지울 수 있어요.
      </p>
      {documents ? <MemoryTabs value={tab} onChange={setTab} /> : null}
      {/* 탭을 바꿔도 펼친 줄과 고치던 글이 남도록 두 판을 모두 그리고 하나만 숨긴다. */}
      <div
        id="memory-panel-memory"
        role={documents ? "tabpanel" : undefined}
        aria-labelledby={documents ? "memory-tab-memory" : undefined}
        hidden={tab !== "memory"}
      >
        {memoryPanel}
      </div>
      {documents ? (
        <div
          id="memory-panel-document"
          role="tabpanel"
          aria-labelledby="memory-tab-document"
          hidden={tab !== "document"}
        >
          {documents}
        </div>
      ) : null}
      {advanced ? (
        <details className="group mt-10 rounded-md border border-border">
          <summary className="cursor-pointer list-none px-4 py-3 text-sm font-medium outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-inset focus-visible:ring-ring/50">
            문서 읽기 토큰
          </summary>
          <div className="border-t border-border px-4 pt-4">{advanced}</div>
        </details>
      ) : null}
    </div>
  );
}
