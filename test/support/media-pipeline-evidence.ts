import type { MediaFixture } from "./media-pipeline-fixture.ts";

export const PIPELINE_STAGES = [
  "uploaded",
  "prepared",
  "embedded",
  "actuallyObserved",
  "draftReferenced",
  "validatedOriginals",
  "editorUploaded",
  "saved",
] as const;
export type PipelineStage = (typeof PIPELINE_STAGES)[number];
export type EvidenceStatus = "PASS" | "FAIL" | "UNMEASURED";
export type EvidenceScope =
  "SYNTHETIC" | "FAKE_HERMES" | "FAKE_CDP" | "REAL_PROVIDER" | "REAL_BROWSER";
export type PipelineEvidenceV1 = {
  schemaVersion: 1;
  runId: string;
  assetKey: string;
  stage: PipelineStage;
  status: EvidenceStatus;
  scope: EvidenceScope;
  byteLength?: number;
  sha256?: string;
  markerRecovered?: boolean;
  durationMs?: number;
  errorCode?: string;
};
const FIELDS = new Set([
  "schemaVersion",
  "runId",
  "assetKey",
  "stage",
  "status",
  "scope",
  "byteLength",
  "sha256",
  "markerRecovered",
  "durationMs",
  "errorCode",
]);
const SCOPES = [
  "SYNTHETIC",
  "FAKE_HERMES",
  "FAKE_CDP",
  "REAL_PROVIDER",
  "REAL_BROWSER",
];
const ERRORS = [
  "DECODE_FAILED",
  "UNSUPPORTED_FORMAT",
  "PAYLOAD_TOO_LARGE",
  "TRUNCATED",
  "MARKER_MISMATCH",
];

export function validatePipelineEvidence(value: unknown): PipelineEvidenceV1 {
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new Error("사건 모양 오류");
  const event = value as PipelineEvidenceV1;
  if (
    Object.keys(event).some((key) => !FIELDS.has(key)) ||
    event.schemaVersion !== 1 ||
    typeof event.runId !== "string" ||
    typeof event.assetKey !== "string" ||
    !/^[a-zA-Z0-9_-]{1,128}$/.test(event.runId) ||
    !/^[a-zA-Z0-9_-]{1,128}$/.test(event.assetKey) ||
    !PIPELINE_STAGES.includes(event.stage) ||
    !["PASS", "FAIL", "UNMEASURED"].includes(event.status) ||
    !SCOPES.includes(event.scope)
  )
    throw new Error("사건 모양 오류");
  for (const key of ["byteLength", "durationMs"] as const) {
    if (
      event[key] !== undefined &&
      (!Number.isSafeInteger(event[key]) || event[key]! < 0)
    )
      throw new Error("수치 오류");
  }
  if (
    event.sha256 !== undefined &&
    (typeof event.sha256 !== "string" || !/^[0-9a-f]{64}$/.test(event.sha256))
  )
    throw new Error("SHA 오류");
  if (
    event.markerRecovered !== undefined &&
    typeof event.markerRecovered !== "boolean"
  )
    throw new Error("표식 오류");
  if (event.errorCode !== undefined && !ERRORS.includes(event.errorCode))
    throw new Error("오류 코드 오류");
  if (event.status === "PASS" && event.errorCode !== undefined)
    throw new Error("성공과 오류 충돌");
  if (event.status !== "PASS" && event.markerRecovered === true)
    throw new Error("미측정 표식 성공 금지");
  return { ...event };
}

/** 합성 lane은 외부 실측 PASS를 만들거나 외부 사건을 인증하지 않는다. */
export function reducePipelineEvidence(
  manifest: readonly MediaFixture[],
  runId: string,
  input: readonly unknown[],
) {
  const assets = new Map(manifest.map((asset) => [asset.assetKey, asset]));
  if (
    assets.size !== manifest.length ||
    new Set(manifest.map((a) => a.expectedMarker)).size !== manifest.length
  ) {
    throw new Error("manifest ID 중복");
  }
  const events = input.map(validatePipelineEvidence);
  const seen = new Set<string>();
  for (const event of events) {
    const asset = assets.get(event.assetKey);
    if (!asset || event.runId !== runId)
      throw new Error("허용하지 않은 ID 또는 실행");
    const key = `${event.assetKey}:${event.stage}:${event.scope}`;
    if (seen.has(key)) throw new Error("중복 사건");
    seen.add(key);
    if (
      (event.scope === "REAL_PROVIDER" || event.scope === "REAL_BROWSER") &&
      event.status !== "UNMEASURED"
    ) {
      throw new Error("합성 lane 외부 실측 금지");
    }
    if (event.sha256 !== undefined && event.sha256 !== asset.bytesSha256)
      throw new Error("다른 asset 바이트");
    if (event.byteLength !== undefined && event.byteLength !== asset.byteLength)
      throw new Error("바이트 길이 불일치");
    if (
      asset.format === "WEBP" &&
      (event.markerRecovered === true ||
        (event.stage === "actuallyObserved" && event.status === "PASS"))
    )
      throw new Error("WebP 실측 없음");
    if (
      event.markerRecovered === true &&
      (event.scope !== "SYNTHETIC" ||
        !asset.markerRecovered ||
        asset.measurement?.recoveredId !== asset.expectedMarker)
    )
      throw new Error("표식 실측 불일치");
    if (event.stage === "actuallyObserved" && event.status === "PASS") {
      if (
        event.scope !== "SYNTHETIC" ||
        event.markerRecovered !== true ||
        event.sha256 !== asset.bytesSha256 ||
        event.byteLength !== asset.byteLength
      )
        throw new Error("관찰 픽셀 증거 없음");
    }
    if (asset.errorCode && event.status === "PASS")
      throw new Error("실패 fixture 성공 금지");
  }
  const ids = (stage: PipelineStage) =>
    manifest
      .filter((asset) =>
        events.some(
          (event) =>
            event.assetKey === asset.assetKey &&
            event.stage === stage &&
            event.status === "PASS",
        ),
      )
      .map((asset) => asset.assetKey);
  const deliveredIds = ids("embedded"),
    observedIds = ids("actuallyObserved"),
    includedIds = ids("draftReferenced");
  const failedObservation = events.some(
    (event) => event.stage === "actuallyObserved" && event.status === "FAIL",
  );
  return {
    events,
    deliveredIds,
    observedIds,
    includedIds,
    omittedIds: manifest
      .filter((asset) => !includedIds.includes(asset.assetKey))
      .map((asset) => asset.assetKey),
    observation:
      observedIds.length === 0
        ? failedObservation
          ? "FAIL"
          : "UNMEASURED"
        : observedIds.length === manifest.length
          ? "PASS"
          : "PARTIAL",
    realProvider: "UNMEASURED",
    realBrowser: "UNMEASURED",
    cost: { value: null, status: "UNMEASURED" },
  };
}

/** 사건 schema보다 좁은 로그 허용 목록으로 원문과 경로를 제외한다. */
export function pipelineLog(value: unknown) {
  const event = validatePipelineEvidence(value);
  const { stage, assetKey, status, byteLength, sha256, durationMs } = event;
  return Object.fromEntries(
    Object.entries({
      stage,
      assetKey,
      status,
      byteLength,
      sha256,
      durationMs,
    }).filter(([, field]) => field !== undefined),
  );
}
