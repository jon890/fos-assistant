import { withParticle } from "./korean-particle.ts";

/** 기억을 누가 남겼는지 목록에 보이는 문구를 만든다. 칸의 뜻은 backend 의 `MemorySource` 가 갖는다. */
export type MemorySourceFields = {
  /** 에이전트가 남긴 기억이면 남긴 실행의 번호다. 사람이 직접 만들었으면 비어 있다. */
  proposedByExecutionId?: number | null;
  /** 남긴 에이전트의 이름이다. 읽는 사용자가 볼 수 있는 에이전트일 때만 온다. */
  sourceAgentName?: string | null;
  sourceAgentDeleted?: boolean;
};

export function isAgentMemory(memory: MemorySourceFields): boolean {
  return memory.proposedByExecutionId != null;
}

export function memorySourceLabel(memory: MemorySourceFields): string {
  if (!isAgentMemory(memory)) return "직접 남김";
  if (memory.sourceAgentDeleted) return "지운 에이전트가 남김";
  const name = memory.sourceAgentName?.trim();
  if (!name) return "에이전트가 남김";
  return `${withSubjectParticle(name)} 남김`;
}

/** 이름 뒤에 주격 조사 「이」 나 「가」 를 붙인다. */
export function withSubjectParticle(name: string): string {
  return withParticle(name, "이", "가");
}
