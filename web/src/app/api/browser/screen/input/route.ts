import { NextResponse } from "next/server";
import { auth } from "@/auth";
import { forwardControlPlane } from "@/lib/control-plane";
import { readControlPlaneResult } from "@/lib/control-plane-result";
import { errorResponse } from "@/lib/api-response";

/** Control Plane 과 같은 본문 바이트 상한이다. */
const MAX_BODY = 8 * 1024;

function tooLarge() {
  return errorResponse("VALIDATION_FAILED", "요청 내용이 너무 커요.", 400);
}

/** 본문을 상한까지만 읽는다. 넘으면 읽기를 끊고 `null` 이다. */
async function readLimited(request: Request): Promise<Uint8Array[] | null> {
  const chunks: Uint8Array[] = [];
  if (!request.body) return chunks;
  const reader = request.body.getReader();
  let size = 0;
  while (true) {
    const { value, done } = await reader.read();
    if (done) return chunks;
    size += value.byteLength;
    if (size > MAX_BODY) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
}

/**
 * 열린 로그인 화면에 입력 하나를 보낸다. 세션을 먼저 보고, 본문은 크기만 확인해 받은 바이트 그대로 넘긴다.
 * 성공하면 본문 없이 204 다.
 */
export async function POST(request: Request) {
  const session = await auth();
  if (!session?.user?.email) {
    return errorResponse("UNAUTHENTICATED", "로그인이 필요해요.", 401);
  }
  if (Number(request.headers.get("content-length") ?? 0) > MAX_BODY) {
    return tooLarge();
  }
  const chunks = await readLimited(request);
  if (chunks === null) return tooLarge();

  const opened = await forwardControlPlane("/api/v1/browser/screen/input", {
    method: "POST",
    body: new Blob(chunks as BlobPart[]).stream(),
    contentType: request.headers.get("content-type") ?? "application/json",
  });
  if (!opened.ok) {
    return errorResponse(opened.code, opened.message, opened.status);
  }
  const result = readControlPlaneResult(
    opened.response.status,
    await opened.response.text(),
  );
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return new NextResponse(null, { status: 204 });
}
