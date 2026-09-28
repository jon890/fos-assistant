import { NextResponse } from "next/server";
import { AGENT_CODE_PATTERN } from "@/lib/agent";
import { requestControlPlane } from "@/lib/control-plane";

type RouteContext = { params: Promise<{ code: string }> };

/** 두 도구 경로의 검증과 Control Plane 응답 전달을 같은 방식으로 처리한다. */
export async function agentToolsRoute(
  request: Request,
  context: RouteContext,
  admin: boolean,
): Promise<NextResponse> {
  const { code } = await context.params;
  if (!AGENT_CODE_PATTERN.test(code)) {
    return NextResponse.json(
      { code: "VALIDATION_FAILED", message: "에이전트 코드 형식이 올바르지 않아요." },
      { status: 400 },
    );
  }

  let body: unknown;
  if (request.method === "PUT") {
    try {
      body = await request.json();
    } catch {
      return NextResponse.json(
        { code: "VALIDATION_FAILED", message: "요청 내용이 올바르지 않아요." },
        { status: 400 },
      );
    }
  }

  const path = `/api/v1/${admin ? "admin/" : ""}agents/${code}/tools`;
  const opened = await requestControlPlane(path, body === undefined ? {} : { method: "PUT", body });
  if (!opened.ok) {
    return NextResponse.json({ code: opened.code, message: opened.message }, { status: opened.status });
  }
  // Control Plane 본문을 그대로 보내 `missingToolsets` 같은 오류 칸을 보존한다.
  return new NextResponse(opened.response.body, {
    status: opened.response.status,
    headers: { "Content-Type": opened.response.headers.get("Content-Type") ?? "application/json" },
  });
}
