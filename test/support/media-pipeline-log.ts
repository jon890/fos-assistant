import type {
  EvidenceScope,
  PipelineEvidenceV1,
} from "./media-pipeline-evidence.ts";

/** Control Plane 의 `MediaPipelineEvents` 가 남기는 본문 없는 단계 사건이다. */
export type BackendPipelineEvent = {
  schemaVersion: 1;
  stage: "embedded" | "draftReferenced";
  conversationId: number;
  draftId?: string;
  revision?: number;
  count: number;
  assets: { assetId: string; position: number }[];
  missing?: string[];
};

const PREFIX = "media-pipeline ";
const ID = /^[1-9][0-9]{0,18}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const KEYS = {
  embedded: ["schemaVersion", "stage", "conversationId", "count", "assets", "missing"],
  draftReferenced: [
    "schemaVersion",
    "stage",
    "conversationId",
    "draftId",
    "revision",
    "count",
    "assets",
  ],
} as const;

/**
 * 사건의 칸을 허용 목록으로만 받는다. 번호와 자리, 장수 밖의 칸이나 글이 섞이면 거절한다.
 * 본문 없는 사건 계약이 깨졌는지를 이 검사가 잡는다.
 */
export function validateBackendPipelineEvent(value: unknown): BackendPipelineEvent {
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new Error("BACKEND_EVENT_INVALID");
  const event = value as BackendPipelineEvent;
  const allowed: readonly string[] | undefined =
    event.stage === "embedded" || event.stage === "draftReferenced"
      ? KEYS[event.stage]
      : undefined;
  const keys = Object.keys(event);
  if (
    !allowed ||
    keys.length !== allowed.length ||
    keys.some((key) => !allowed.includes(key)) ||
    event.schemaVersion !== 1 ||
    !Number.isSafeInteger(event.conversationId) ||
    event.conversationId < 1 ||
    !Number.isSafeInteger(event.count) ||
    !Array.isArray(event.assets) ||
    event.count !== event.assets.length
  )
    throw new Error("BACKEND_EVENT_INVALID");
  let last = -1;
  for (const asset of event.assets) {
    if (
      !asset ||
      typeof asset !== "object" ||
      Object.keys(asset).length !== 2 ||
      typeof asset.assetId !== "string" ||
      !ID.test(asset.assetId) ||
      !Number.isSafeInteger(asset.position) ||
      asset.position <= last
    )
      throw new Error("BACKEND_EVENT_INVALID");
    last = asset.position;
  }
  const ids = event.assets.map((asset) => asset.assetId);
  if (event.stage === "embedded") {
    if (
      !Array.isArray(event.missing) ||
      event.missing.some((id) => typeof id !== "string" || !ID.test(id)) ||
      event.assets.some((asset, index) => asset.position !== index)
    )
      throw new Error("BACKEND_EVENT_INVALID");
    ids.push(...event.missing);
  } else if (
    typeof event.draftId !== "string" ||
    !UUID.test(event.draftId) ||
    !Number.isSafeInteger(event.revision) ||
    event.revision! < 1
  )
    throw new Error("BACKEND_EVENT_INVALID");
  if (new Set(ids).size !== ids.length) throw new Error("BACKEND_EVENT_DUPLICATE_ID");
  return event;
}

/** 로그 줄에서 `media-pipeline {...}` 만 골라 검사한다. 다른 줄은 보지 않는다. */
export function parseBackendPipelineEvents(log: string): BackendPipelineEvent[] {
  const events: BackendPipelineEvent[] = [];
  for (const line of log.split("\n")) {
    const start = line.indexOf(`${PREFIX}{`);
    if (start < 0) continue;
    let parsed: unknown;
    try {
      parsed = JSON.parse(line.slice(start + PREFIX.length));
    } catch {
      throw new Error("BACKEND_EVENT_INVALID");
    }
    events.push(validateBackendPipelineEvent(parsed));
  }
  return events;
}

/**
 * Control Plane 사건을 첨부 번호로 manifest 의 assetKey 에 잇는다. 이 실행이 올리지 않은 번호가 섞이면 거절한다.
 * 실은 사진은 PASS, 싣지 못한 사진은 FAIL 이다. 사건은 단계가 일어난 사실만 뜻하며 판독 성공을 만들지 않는다.
 */
export function backendEvidence(
  event: BackendPipelineEvent,
  runId: string,
  assetKeys: ReadonlyMap<string, string>,
  scope: EvidenceScope,
): PipelineEvidenceV1[] {
  const keyOf = (assetId: string) => {
    const assetKey = assetKeys.get(assetId);
    if (!assetKey) throw new Error("BACKEND_EVENT_FOREIGN_ID");
    return assetKey;
  };
  const passed = event.assets.map(
    (asset): PipelineEvidenceV1 => ({
      schemaVersion: 1,
      runId,
      assetKey: keyOf(asset.assetId),
      stage: event.stage,
      status: "PASS",
      scope,
    }),
  );
  const failed = (event.missing ?? []).map(
    (assetId): PipelineEvidenceV1 => ({
      schemaVersion: 1,
      runId,
      assetKey: keyOf(assetId),
      stage: event.stage,
      status: "FAIL",
      scope,
    }),
  );
  return [...passed, ...failed];
}
