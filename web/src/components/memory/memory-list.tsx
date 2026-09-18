"use client";

import { useState } from "react";
import { MemoryForm } from "./memory-form";
import { MemoryItem } from "./memory-item";
import { MemoryProposal } from "./memory-proposal";

export type Memory = { id: number; scope: "USER" | "FAMILY"; ownerUserId: number | null; title: string; content: string; alwaysInject: boolean; status: "PROPOSED" | "ACCEPTED" | "REJECTED"; omittedFromContext?: boolean };

function Section({ title, children }: { title: string; children: React.ReactNode }) { return <section className="mb-8"><h2 className="mb-3 text-lg font-semibold">{title}</h2><div className="grid gap-3">{children}</div></section>; }

export function MemoryList({ initialMemories, isAdmin, currentUserId }: { initialMemories: Memory[]; isAdmin: boolean; currentUserId?: number }) {
  const [memories, setMemories] = useState(initialMemories);
  async function reload() { const response = await fetch("/api/memories", { cache: "no-store" }); if (response.ok) { const next = await response.json() as Memory[]; setMemories(next); window.dispatchEvent(new CustomEvent("memory-proposal-count", { detail: next.filter((memory) => memory.status === "PROPOSED").length })); } }
  const proposals = memories.filter((memory) => memory.status === "PROPOSED");
  const family = memories.filter((memory) => memory.status === "ACCEPTED" && memory.scope === "FAMILY");
  const personal = memories.filter((memory) => memory.status === "ACCEPTED" && memory.scope === "USER");
  return <div className="mx-auto w-full max-w-4xl"><h1 className="mb-2 text-xl font-semibold">기억</h1><p className="mb-6 max-w-2xl text-sm leading-6 text-muted">받아들인 Memory만 다음 대화에 사용합니다.</p><MemoryForm isAdmin={isAdmin} onCreated={reload} />{proposals.length > 0 ? <Section title="받을지 정할 것">{proposals.map((memory) => <MemoryProposal key={memory.id} memory={memory} onChanged={reload} />)}</Section> : null}<Section title="우리 가족이 함께 아는 것">{family.length > 0 ? family.map((memory) => <MemoryItem key={memory.id} memory={memory} canEdit={isAdmin} onChanged={reload} />) : <p className="text-sm text-muted">아직 가족 공용 Memory가 없습니다.</p>}</Section><Section title="나에 대해 아는 것">{personal.length > 0 ? personal.map((memory) => <MemoryItem key={memory.id} memory={memory} canEdit={memory.ownerUserId === currentUserId} onChanged={reload} />) : <p className="text-sm text-muted">아직 개인 Memory가 없습니다.</p>}</Section></div>;
}
