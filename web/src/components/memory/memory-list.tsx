"use client";

import { useState } from "react";
import { fetchMemories } from "@/lib/memory-api";
import {
  fetchPendingSupersedes,
  type SupersedeProposal,
} from "@/lib/memory-link-api";
import {
  fetchMaintenanceCandidates,
  type MaintenanceCandidate,
} from "@/lib/memory-maintenance-api";
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
import { MemoryMaintenanceSection } from "./memory-maintenance-section";
import { MemoryReviewSection } from "./memory-review-section";
import { MemoryTabs, type MemoryTab } from "./memory-tabs";
import { useMemoryFocus } from "./use-memory-focus";

export type Memory = MemorySourceFields & {
  id: number;
  scope: "USER" | "GROUP";
  ownerUserId: number | null;
  title: string;
  /** 민감한 기억은 빈 글이다. */
  content: string;
  alwaysInject: boolean;
  /** 정비가 자동으로 보관해도 된다고 허락한 기억이다. 서버가 보내지 않으면 허락하지 않은 것으로 본다. */
  archivable?: boolean;
  status: "PROPOSED" | "ACCEPTED" | "REJECTED";
  sensitive?: boolean;
  omittedFromContext?: boolean;
  /** 이 기억을 대신한 기억이다. 대체된 기억은 다음 대화부터 쓰이지 않는다. 제목은 볼 수 없으면 null 이다. */
  supersededBy?: {
    linkId: number;
    memoryId: number | null;
    title: string | null;
  } | null;
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
  initialSupersedes = [],
  initialCandidates = [],
  focusMemoryId,
  isAdmin,
  currentUserId,
  readAt,
  documents,
}: {
  initialMemories: Memory[];
  /** 받아들이거나 거절할 대체 제안이다. 읽지 못했으면 빈 목록이다. */
  initialSupersedes?: SupersedeProposal[];
  /** 확인할 정비 후보다. 읽지 못했으면 빈 목록이다. */
  initialCandidates?: MaintenanceCandidate[];
  /** 주소의 `memory` 값이다. 그 줄을 펼치고 스크롤한다. */
  focusMemoryId?: number;
  isAdmin: boolean;
  currentUserId?: number;
  /** 서버가 목록을 읽은 시각이다. 서버와 브라우저가 같은 상대 시각을 그리도록 지금 시각 대신 쓴다 */
  readAt: string;
  /** 「문서」 탭에 그릴 절이다. 문서를 읽지 못했으면 비어 있고 탭을 보이지 않는다. */
  documents?: React.ReactNode;
}) {
  const [memories, setMemories] = useState(initialMemories);
  const [supersedes, setSupersedes] = useState(initialSupersedes);
  const [candidates, setCandidates] = useState(initialCandidates);
  const [tab, setTab] = useState<MemoryTab>("memory");
  const [filter, setFilter] = useState<MemoryFilter>("ALL");
  /** 펼친 줄이다. 한 번에 하나만 펼친다. */
  const [openId, setOpenId] = useState<number | null>(null);
  /** 화면을 처음 그릴 때 있던 기억들이다. 여기 없는 줄만 새 줄로 보고 등장 움직임을 준다 */
  const [initialIds] = useState(
    () => new Set(initialMemories.map((memory) => memory.id)),
  );
  useMemoryFocus(focusMemoryId, { setOpenId, setTab, setFilter });
  async function reload() {
    const [response, pending, candidateResponse] = await Promise.all([
      fetchMemories(),
      fetchPendingSupersedes().catch(() => null),
      fetchMaintenanceCandidates().catch(() => null),
    ]);
    if (candidateResponse?.ok)
      setCandidates((await candidateResponse.json()) as MaintenanceCandidate[]);
    if (pending?.ok)
      setSupersedes((await pending.json()) as SupersedeProposal[]);
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
      <MemoryReviewSection
        proposals={proposals}
        supersedes={supersedes}
        openId={openId}
        readAt={readAt}
        onToggle={toggle}
        onChanged={reload}
      />
      <MemoryMaintenanceSection
        candidates={candidates}
        onReviewed={(id) =>
          setCandidates((rows) => rows.filter((row) => row.id !== id))
        }
      />
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
    </div>
  );
}
