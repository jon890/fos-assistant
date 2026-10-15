import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";
import { isPublicId } from "@/lib/conversation-id";

/** 화면이 STALE·삭제·남의 것을 가려 안내하도록 그대로 넘기는 code 다. 그 밖의 code 는 `INTERNAL_ERROR` 로 바꾼다. */
const PASSED_CODES = new Set([
  "CONTENT_DRAFT_STALE",
  "CONTENT_DRAFT_GONE",
  "CONTENT_DRAFT_UNAVAILABLE",
  "CONTENT_DRAFT_JOB_UNAVAILABLE",
  "CONVERSATION_NOT_FOUND",
  "VALIDATION_FAILED",
  "UNAUTHENTICATED",
  "FORBIDDEN",
]);

/**
 * 원고 snapshot 과 job 을 읽는 GET 을 Control Plane 에 넘긴다.
 *
 * <p>경로와 cursor 의 ID 는 UUID 모양만 통과시킨다. 사용자는 세션이 정하므로 요청이 주인을 고를 수 없다.
 * Control Plane 의 오류 원문은 넘기지 않고 문구를 다시 정한다.
 */
export async function contentDraftGet(
  path: string,
  ids: string[],
  pick: (body: unknown) => unknown,
) {
  if (!ids.every(isPublicId))
    return errorResponse("VALIDATION_FAILED", "주소가 올바르지 않아요.", 400);
  const result = await callControlPlane<unknown>(path);
  if (result.ok) {
    try {
      return NextResponse.json(pick(result.data), { status: result.status });
    } catch {
      return errorResponse("INTERNAL_ERROR", "원고를 불러오지 못했어요.", 502);
    }
  }
  const code = PASSED_CODES.has(result.code) ? result.code : "INTERNAL_ERROR";
  return errorResponse(code, "원고를 불러오지 못했어요.", result.status);
}

// 아래는 화면이 쓰는 칸만 옮긴다. snapshotHash, jobId, 오류 code 처럼 화면이 읽지 않는 칸과
// Control Plane 이 뒤에 더하는 칸은 브라우저로 보내지 않는다.

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value))
    throw new Error();
  return value as Record<string, unknown>;
}

function text(value: unknown): string {
  if (typeof value !== "string") throw new Error();
  return value;
}

function texts(value: unknown): string[] {
  if (!Array.isArray(value)) throw new Error();
  return value.map(text);
}

function block(value: unknown) {
  const item = record(value);
  switch (item.type) {
    case "paragraph":
      return { type: "paragraph", text: text(item.text) };
    case "image":
      return {
        type: "image",
        assetId: text(item.assetId),
        caption: typeof item.caption === "string" ? item.caption : null,
      };
    case "sticker":
      return { type: "sticker", code: text(item.code) };
    case "map":
      return {
        type: "map",
        name: text(item.name),
        address: text(item.address),
      };
    default:
      throw new Error();
  }
}

export function pickSnapshot(body: unknown) {
  const item = record(body);
  const preview = record(item.preview);
  if (!Number.isSafeInteger(item.revision)) throw new Error();
  if (!Array.isArray(preview.blocks)) throw new Error();
  return {
    revision: item.revision,
    preview: {
      title: text(preview.title),
      category: typeof preview.category === "string" ? preview.category : null,
      tags: texts(preview.tags),
      blocks: preview.blocks.map(block),
    },
    assetIds: texts(item.assetIds),
    omittedAssetIds: texts(item.omittedAssetIds),
  };
}

/** 실패 사유는 worker 가 정한 고정 코드 모양만 넘긴다. */
function reasonCode(value: unknown) {
  return typeof value === "string" && /^[a-z][a-z_]{0,63}$/.test(value)
    ? value
    : null;
}

export function pickJob(body: unknown) {
  const item = record(body);
  if (!Number.isSafeInteger(item.revision)) throw new Error();
  return {
    snapshotId: text(item.snapshotId),
    draftId: text(item.draftId),
    revision: item.revision,
    actionId: text(item.actionId),
    status: text(item.status),
    stage: typeof item.stage === "string" ? item.stage : null,
    saveClicked: item.saveClicked === true,
    errorReason: reasonCode(item.errorReason),
  };
}

export function pickJobPage(body: unknown) {
  const item = record(body);
  if (!Array.isArray(item.items)) throw new Error();
  return {
    items: item.items.map(pickJob),
    nextBeforeSnapshotId:
      item.nextBeforeSnapshotId == null
        ? null
        : text(item.nextBeforeSnapshotId),
  };
}
