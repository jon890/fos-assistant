import { NextResponse } from "next/server";
import { forwardControlPlane } from "@/lib/control-plane";

/** 대화 식별자나 대기 메시지 번호가 형식에 맞지 않을 때의 응답이다. */
export function invalidPendingRequest() {
  return NextResponse.json(
    { code: "VALIDATION_FAILED", message: "요청 내용이 올바르지 않아요." },
    { status: 400 },
  );
}

/**
 * 대기 메시지 경로를 Control Plane 으로 넘기고 그 응답의 상태와 본문을 그대로 돌려준다.
 *
 * <p>201, 202, 204 와 오류 본문의 `code` 를 화면이 그대로 읽는다. 그래서 다시 감싸지 않는다.
 */
export async function forwardPending(
  path: string,
  init: { method: string; body?: Record<string, unknown> },
): Promise<Response> {
  const opened = await forwardControlPlane(path, {
    method: init.method,
    body:
      init.body === undefined
        ? undefined
        : new Blob([JSON.stringify(init.body)]).stream(),
    contentType: init.body === undefined ? undefined : "application/json",
  });
  if (!opened.ok) {
    return NextResponse.json(
      { code: opened.code, message: opened.message },
      { status: opened.status },
    );
  }
  const upstream = opened.response;
  const contentType = upstream.headers.get("Content-Type");
  // 본문이 없는 상태에 본문을 실으면 `Response` 가 던진다. 취소의 204 가 그렇다.
  return new Response(upstream.status === 204 ? null : upstream.body, {
    status: upstream.status,
    headers: contentType ? { "Content-Type": contentType } : undefined,
  });
}
