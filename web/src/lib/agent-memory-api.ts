import { memoryRequest, type MemoryApiResult } from "@/lib/memory-api";
import type { AgentMemorySetting } from "@/lib/agent-memory";

export function getAgentMemorySetting(
  code: string,
): Promise<MemoryApiResult<AgentMemorySetting>> {
  return memoryRequest(
    `/api/admin/agents/${code}/memory-collections`,
    "기억 영역을 불러오지 못했어요.",
  );
}

/** 에이전트가 받을 영역 전체를 보낸다. 빈 목록이면 모두 뗀다. */
export function saveAgentMemorySetting(
  code: string,
  collections: { collection: string; allowSensitive: boolean }[],
): Promise<MemoryApiResult<AgentMemorySetting>> {
  return memoryRequest(
    `/api/admin/agents/${code}/memory-collections`,
    "기억 영역을 저장하지 못했어요.",
    { method: "PUT", body: { collections } },
  );
}
