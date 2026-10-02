import { describeFailure } from "@/components/error-message";

/** 실패하면 사용자에게 보일 문구와 오류 코드를 함께 돌려준다. 코드가 없으면 요청이 닿지 못한 것이다. */
export type MemoryApiResult<T> =
  { ok: true; data: T } | { ok: false; message: string; code: string | null };

async function failure(response: Response): Promise<MemoryApiResult<never>> {
  // describeFailure 가 본문을 읽으므로 코드를 먼저 복제본에서 꺼낸다.
  const payload = (await response
    .clone()
    .json()
    .catch(() => null)) as { code?: unknown } | null;
  return {
    ok: false,
    message: await describeFailure(response),
    code: typeof payload?.code === "string" ? payload.code : null,
  };
}

export async function memoryRequest<T>(
  path: string,
  fallback: string,
  init?: { method: string; body?: unknown },
): Promise<MemoryApiResult<T>> {
  try {
    const response = await fetch(path, {
      method: init?.method ?? "GET",
      headers:
        init?.body === undefined
          ? undefined
          : { "Content-Type": "application/json" },
      body: init?.body === undefined ? undefined : JSON.stringify(init.body),
      cache: "no-store",
    });
    if (!response.ok) return await failure(response);
    // 지우기는 본문 없이 성공한다.
    const text = await response.text();
    return { ok: true, data: (text === "" ? null : JSON.parse(text)) as T };
  } catch {
    return { ok: false, message: fallback, code: null };
  }
}

/** 기억 화면이 부르는 요청이다. 응답을 읽고 오류를 보이는 일은 화면이 맡는다. */

export function fetchMemories(): Promise<Response> {
  return fetch("/api/memories", { cache: "no-store" });
}

export function createMemory(input: {
  scope: string;
  title: FormDataEntryValue | null;
  content: FormDataEntryValue | null;
  alwaysInject: boolean;
}): Promise<Response> {
  return fetch("/api/memories", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
}

export function updateMemory(
  id: number,
  input: { content: FormDataEntryValue | null; alwaysInject: boolean },
): Promise<Response> {
  return fetch(`/api/memories/${id}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
}

export function deleteMemory(id: number): Promise<Response> {
  return fetch(`/api/memories/${id}`, { method: "DELETE" });
}

export function decideMemoryProposal(
  id: number,
  action: "accept" | "reject",
): Promise<Response> {
  return fetch(`/api/memories/${id}/${action}`, { method: "POST" });
}
