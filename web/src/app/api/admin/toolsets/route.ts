import { errorResponse } from "@/lib/api-response";
import { requestControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";

/** 관리자가 도구 목록을 읽고 그룹의 숨김 목록을 저장한다. */
async function forward(request: Request) {
  let body: unknown;
  if (request.method === "PUT") {
    const parsed = await readJsonBody(request);
    if (!parsed.ok) return parsed.response;
    body = parsed.body;
  }
  const opened = await requestControlPlane("/api/v1/admin/toolsets", {
    method: request.method,
    ...(body === undefined ? {} : { body }),
  });
  if (!opened.ok)
    return errorResponse(opened.code, opened.message, opened.status);
  return new Response(opened.response.body, {
    status: opened.response.status,
    headers: { "Content-Type": "application/json" },
  });
}

export const GET = forward;
export const PUT = forward;
