import { errorResponse } from "@/lib/api-response";
import { requestControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";

/** 웹 세션을 사용해 도구 요청 응답을 그대로 전달한다. */
export async function toolsetRequestRoute(request: Request, resource: string) {
  let body: unknown;
  if (request.method === "POST" && !resource.endsWith("/cancel")) {
    const parsed = await readJsonBody(request);
    if (!parsed.ok) return parsed.response;
    body = parsed.body;
  }
  const opened = await requestControlPlane(`/api/v1/${resource}`, {
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
