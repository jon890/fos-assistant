import { createHash } from "node:crypto";
import { compileContentDraft } from "./content-compiler.ts";
import {
  compiledSnapshotV1Schema,
  type CanonicalBlock,
  type CompiledSnapshotV1,
  type ResolvedAsset,
} from "./content-draft.ts";
import type { Env } from "./session.ts";

/** private start 자식과 worker 에만 주는 env. 일반 설치와 profile 은 이 이름을 선언하지 못한다. */
export const TICKET_ENV = "FOS_CONTENT_SAVE_TICKET";
export const AUTHORIZE_URL_ENV = "FOS_CONTENT_SAVE_AUTHORIZE_URL";
/** plugin 이 기존 Control Plane 주소 뒤에 붙이는 고정 경로. 요청자가 다른 callback 을 고르지 못한다. */
export const AUTHORIZE_PATH = "/internal/content-draft-jobs/authorize";
const AUTHORIZE_TIMEOUT_MS = 10_000;
const COMPILED_CAP = 262144;
// compile 결과에는 대화 ID 가 없다. 재계산에만 쓰는 자리값이다.
const PLACEHOLDER_CONVERSATION = "00000000-0000-4000-8000-000000000000";

export type SnapshotGuardCode =
  "snapshot_invalid" | "authorize_denied" | "photo_mismatch";

/** 원고 snapshot 검사가 막은 이유. 본문·경로·ticket 을 싣지 않는다. */
export class SnapshotGuardError extends Error {
  constructor(readonly code: SnapshotGuardCode) {
    super(code);
  }
}

export type SaveStage = "start" | "photo" | "save";
export type Fetcher = (url: string, init: RequestInit) => Promise<Response>;
export type SaveAuthorizer = (
  stage: SaveStage,
  position?: number,
) => Promise<void>;

/** 사진 업로드 직전 검사. manifest 는 DSL 사진 순서와 같은 순서다. */
export type SnapshotGuard = {
  manifest: CompiledSnapshotV1["assetManifest"];
  authorizePhoto: (position: number) => Promise<void>;
};

export const jsonBytes = (value: unknown) =>
  Buffer.byteLength(JSON.stringify(value), "utf8");

export const fingerprintOf = (bytes: Uint8Array) =>
  createHash("sha256").update(bytes).digest("hex");

/** 고정 경로로 끝나는 http(s) 주소만 받는다. 계정·query·fragment 가 있으면 쓰지 않는다. */
export function authorizeUrl(value: string | undefined): string | null {
  try {
    const url = new URL(value ?? "");
    if (
      (url.protocol === "http:" || url.protocol === "https:") &&
      !url.username &&
      !url.password &&
      !url.search &&
      !url.hash &&
      url.pathname.endsWith(AUTHORIZE_PATH)
    )
      return url.href;
  } catch {
    // 주소가 아니면 아래에서 거절한다.
  }
  return null;
}

function blockOf(
  tuple: CompiledSnapshotV1["canonicalBlocks"][number],
): CanonicalBlock {
  switch (tuple[0]) {
    case "paragraph":
      return { type: "paragraph", text: tuple[1] };
    case "image":
      return {
        type: "image",
        assetId: tuple[1],
        ...(tuple[2] === null ? {} : { caption: tuple[2] }),
        ...(tuple[3] === null
          ? {}
          : { observationRef: { id: tuple[3][0], revision: tuple[3][1] } }),
      };
    case "sticker":
      return { type: "sticker", code: tuple[1] };
    case "map":
      return { type: "map", name: tuple[1], address: tuple[2] };
  }
}

/**
 * 받은 snapshot 의 canonical tuple·manifest·notes 로 원고를 다시 만들어 compiler 를 다시 돌린다.
 * 결과 전체와 두 지문이 받은 값과 정확히 같을 때만 돌려준다. 어긋나면 `null` 이다.
 */
export function verifySnapshot(raw: unknown): CompiledSnapshotV1 | null {
  const parsed = compiledSnapshotV1Schema.safeParse(raw);
  if (!parsed.success || jsonBytes(parsed.data) > COMPILED_CAP) return null;
  const snapshot = parsed.data;
  const assets = new Map<string, ResolvedAsset>();
  for (const [index, entry] of snapshot.assetManifest.entries()) {
    if (assets.has(entry.assetId)) continue;
    const note = snapshot.photoNotes[String(index + 1)];
    assets.set(entry.assetId, {
      assetId: entry.assetId,
      sourceFingerprint: entry.sourceFingerprint,
      fileName: entry.fileName,
      photoDir: snapshot.payload.photo_dir ?? "",
      ordinal: assets.size + 1,
      ...(note === undefined ? {} : { observationNote: note }),
    });
  }
  try {
    const again = compileContentDraft(
      {
        schemaVersion: 1,
        draftId: snapshot.draftId,
        revision: snapshot.revision,
        conversationId: PLACEHOLDER_CONVERSATION,
        title: snapshot.payload.title,
        category: snapshot.payload.category,
        tags: [...snapshot.payload.tags],
        blocks: snapshot.canonicalBlocks.map(blockOf),
      },
      [...assets.values()],
    );
    return JSON.stringify(again) === JSON.stringify(snapshot) ? snapshot : null;
  } catch {
    return null;
  }
}

/**
 * worker env 의 ticket 과 authorize 주소를 꺼내고 env 에서 바로 지운다. 둘 중 하나라도 없거나 주소가 틀리면 `null` 이다.
 * 응답은 HTTP 200 의 정확한 `{"allowed":true}` 만 허가다. 오류·timeout·다른 모양은 거절이며 다시 보내지 않는다.
 */
export function takeAuthorizer(
  env: Env,
  fetcher: Fetcher = (url, init) => fetch(url, init),
): SaveAuthorizer | null {
  const ticket = env[TICKET_ENV];
  const url = authorizeUrl(env[AUTHORIZE_URL_ENV]);
  delete env[TICKET_ENV];
  delete env[AUTHORIZE_URL_ENV];
  if (!ticket || !url) return null;
  return async (stage, position) => {
    let allowed = false;
    try {
      const response = await fetcher(url, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(
          position === undefined
            ? { ticket, stage }
            : { ticket, stage, position },
        ),
        redirect: "error",
        signal: AbortSignal.timeout(AUTHORIZE_TIMEOUT_MS),
      });
      const text = response.status === 200 ? await response.text() : "";
      allowed =
        text.length <= 64 &&
        JSON.stringify(JSON.parse(text)) === '{"allowed":true}';
    } catch {
      allowed = false;
    }
    if (!allowed) throw new SnapshotGuardError("authorize_denied");
  };
}
