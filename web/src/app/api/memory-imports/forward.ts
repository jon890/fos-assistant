import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";
import { MAX_IMPORT_BYTES } from "@/lib/memory-import";

const TOO_LARGE = {
  code: "MEMORY_IMPORT_TOO_LARGE",
  message: "가져올 파일이 너무 커요. 나눠서 올려 주세요.",
};

function tooLarge() {
  return NextResponse.json(TOO_LARGE, {
    status: 413,
    headers: { "Cache-Control": "no-store" },
  });
}

/** 본문을 상한까지만 읽는다. 넘으면 나머지를 읽지 않고 null 을 낸다. */
async function readLimited(request: Request): Promise<string | null> {
  const reader = request.body?.getReader();
  if (!reader) return "";
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > MAX_IMPORT_BYTES) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  return Buffer.concat(chunks).toString("utf8");
}

/**
 * 묶음을 Control Plane 의 들이기 경로로 넘긴다. 상한을 넘으면 Control Plane 을 부르지 않고 413 으로 답한다.
 * 본문은 읽어서 그대로 넘길 뿐 어디에도 남기지 않는다(ADR-058).
 */
export async function forwardImport(request: Request, path: string) {
  const declared = Number(request.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > MAX_IMPORT_BYTES)
    return tooLarge();
  const raw = await readLimited(request);
  if (raw === null) return tooLarge();
  let body: unknown;
  try {
    body = JSON.parse(raw);
  } catch {
    return errorResponse(
      "VALIDATION_FAILED",
      "요청 내용이 올바르지 않아요.",
      400,
    );
  }
  const result = await callControlPlane(path, { method: "POST", body });
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data, {
    headers: { "Cache-Control": "no-store" },
  });
}
