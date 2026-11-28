import { z } from "zod";
import { CareerError, type CareerErrorCode, type FetchLike } from "./errors.ts";

const tokenSchema = z.object({ access_token: z.string().min(1) });

// 토큰 통로의 401은 표식 오류, 409는 재연결 필요, 404는 사라진 연결이다.
function channelCode(status: number): CareerErrorCode {
  if (status === 401 || status === 409) return "CAREER_GITHUB_UNAUTHORIZED";
  if (status === 404) return "CAREER_GITHUB_NOT_CONFIGURED";
  return "CAREER_GITHUB_UNAVAILABLE";
}

function channelAddress(url: string): string | undefined {
  try {
    const parsed = new URL(url);
    return parsed.protocol === "http:" || parsed.protocol === "https:" ? parsed.toString() : undefined;
  } catch {
    return undefined;
  }
}

/** 도구를 부를 때마다 통로에서 최신 토큰을 받아 메모리에서만 쓴다. */
export function channelToken(url: string, fetchImpl: FetchLike = fetch): () => Promise<string> {
  const address = channelAddress(url);
  // GitHub 연결 설정 오류는 GitHub 도구를 부를 때만 알린다.
  if (!address)
    return async () => {
      throw new CareerError("CAREER_GITHUB_NOT_CONFIGURED");
    };
  return async () => {
    let response: Response;
    try {
      response = await fetchImpl(address, {
        method: "GET",
        headers: { Accept: "application/json" },
        redirect: "error",
        signal: AbortSignal.timeout(5_000),
      });
    } catch {
      throw new CareerError("CAREER_GITHUB_UNAVAILABLE");
    }
    if (!response.ok) {
      // 오류 본문에 표식이 들어 있을 수 있어 읽지 않고 연결을 놓는다.
      await response.body?.cancel().catch(() => undefined);
      throw new CareerError(channelCode(response.status));
    }
    let body: unknown;
    try {
      body = await response.json();
    } catch {
      throw new CareerError("CAREER_INVALID_RESPONSE");
    }
    const token = tokenSchema.safeParse(body);
    if (!token.success || !token.data.access_token.trim()) throw new CareerError("CAREER_INVALID_RESPONSE");
    return token.data.access_token.trim();
  };
}
