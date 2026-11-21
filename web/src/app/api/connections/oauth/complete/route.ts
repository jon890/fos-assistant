import {
  connectionResponse,
  invalidConnectionRequest,
} from "@/lib/connection-route";
import { safeCompletion } from "@/lib/connection-oauth";
import { oauthCall } from "@/lib/connection-oauth-route";
import { readJsonBody } from "@/lib/json-body";
import { rejectCrossOrigin } from "@/lib/same-origin";

const KEYS = ["state", "code", "error", "setupAction"] as const;

/** 서비스 화면에서 돌아온 주소의 검색어를 넘겨 연결을 마친다. 실패도 200 의 `outcome` 으로 온다. */
export async function POST(request: Request) {
  const rejected = rejectCrossOrigin(request);
  if (rejected) return rejected;
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const query: Record<string, string | null> = {};
  for (const key of KEYS) {
    const value = parsed.body[key] ?? null;
    if (value !== null && (typeof value !== "string" || value.length > 4096))
      return invalidConnectionRequest();
    query[key] = value;
  }
  return connectionResponse(
    await oauthCall(
      "/api/v1/connections/oauth/complete",
      query,
      safeCompletion,
    ),
  );
}
