import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, test } from "node:test";
import {
  createMediaFixture,
  mediaMetadataCases,
} from "../support/media-pipeline-fixture.ts";
import {
  PIPELINE_STAGES,
  pipelineLog,
  reducePipelineEvidence,
  validatePipelineEvidence,
} from "../support/media-pipeline-evidence.ts";
import type {
  PipelineEvidenceV1,
  PipelineStage,
} from "../support/media-pipeline-evidence.ts";

const dir = mkdtempSync(join(tmpdir(), "media-evidence-"));
const manifest = createMediaFixture(dir, 30);
after(() => rmSync(dir, { recursive: true, force: true }));
const runId = "synthetic-run";
function event(
  index = 0,
  stage: PipelineStage = "actuallyObserved",
): PipelineEvidenceV1 {
  const asset = manifest[index];
  return {
    schemaVersion: 1,
    runId,
    assetKey: asset.assetKey,
    stage,
    status: "PASS",
    scope: "SYNTHETIC",
    byteLength: asset.byteLength,
    sha256: asset.bytesSha256!,
    ...(stage === "actuallyObserved" ? { markerRecovered: true } : {}),
  };
}

test("각 단계의 30개 ID와 실제 픽셀 관찰을 따로 집계한다", () => {
  const input = PIPELINE_STAGES.flatMap((stage) =>
    manifest.map((_, i) => event(i, stage)),
  );
  const result = reducePipelineEvidence(manifest, runId, input);
  assert.equal(result.events.length, 240);
  assert.deepEqual(
    result.deliveredIds,
    manifest.map((asset) => asset.assetKey),
  );
  assert.deepEqual(result.observedIds, result.deliveredIds);
  assert.deepEqual(result.includedIds, result.deliveredIds);
  assert.deepEqual(result.omittedIds, []);
  assert.equal(result.observation, "PASS");
  assert.equal(result.realProvider, "UNMEASURED");
  assert.equal(result.realBrowser, "UNMEASURED");
  assert.deepEqual(result.cost, { value: null, status: "UNMEASURED" });
});

test("embedded와 saved만으로 관찰 성공을 만들지 않는다", () => {
  const result = reducePipelineEvidence(manifest, runId, [
    event(0, "embedded"),
    event(0, "saved"),
  ]);
  assert.deepEqual(result.observedIds, []);
  assert.equal(result.observation, "UNMEASURED");
  assert.throws(
    () =>
      reducePipelineEvidence(manifest, runId, [
        { ...event(), markerRecovered: undefined },
      ]),
    /픽셀/,
  );
});

test("partial 관찰과 원고 subset의 빠진 ID가 다르다", () => {
  const result = reducePipelineEvidence(manifest, runId, [
    event(0),
    event(1),
    event(1, "draftReferenced"),
  ]);
  assert.equal(result.observation, "PARTIAL");
  assert.deepEqual(result.observedIds, ["synthetic-1", "synthetic-2"]);
  assert.deepEqual(result.includedIds, ["synthetic-2"]);
  assert.equal(result.omittedIds.length, 29);
  assert.ok(result.omittedIds.includes("synthetic-1"));
});

test("무단 ID, 교차 표식, 바이트, 중복 사건과 실행 혼합은 거절한다", () => {
  for (const bad of [
    { ...event(), assetKey: "unknown" },
    { ...event(), runId: "other" },
    { ...event(), sha256: manifest[1].bytesSha256 },
    { ...event(), byteLength: 1 },
  ]) {
    assert.throws(() => reducePipelineEvidence(manifest, runId, [bad]));
  }
  assert.throws(
    () => reducePipelineEvidence(manifest, runId, [event(), event()]),
    /중복/,
  );
  const crossed = manifest.map((asset, index) =>
    index === 0
      ? {
          ...asset,
          measurement: { ...asset.measurement!, recoveredId: 2 },
        }
      : asset,
  );
  assert.throws(
    () => reducePipelineEvidence(crossed, runId, [event()]),
    /표식/,
  );
  assert.throws(
    () => reducePipelineEvidence([manifest[0], manifest[0]], runId, []),
    /중복/,
  );
});

test("합성 성공은 provider와 browser 실측으로 승격하지 않는다", () => {
  for (const scope of [
    "REAL_PROVIDER",
    "REAL_BROWSER",
    "FAKE_HERMES",
    "FAKE_CDP",
  ] as const) {
    assert.throws(() =>
      reducePipelineEvidence(manifest, runId, [{ ...event(), scope }]),
    );
  }
  const result = reducePipelineEvidence(manifest, runId, [
    { ...event(0, "embedded"), scope: "REAL_PROVIDER", status: "UNMEASURED" },
  ]);
  assert.deepEqual(result.observedIds, []);
});

test("WebP metadata와 손상, 미지원, 413, 잘림을 구분한다", () => {
  const cases = mediaMetadataCases();
  const base = {
    schemaVersion: 1,
    runId,
    stage: "actuallyObserved",
    scope: "SYNTHETIC",
  };
  const result = reducePipelineEvidence(
    cases,
    runId,
    cases.map((asset) => ({
      ...base,
      assetKey: asset.assetKey,
      status: asset.actuallyObserved,
      ...(asset.errorCode ? { errorCode: asset.errorCode } : {}),
    })),
  );
  assert.equal(result.observation, "FAIL");
  assert.equal(result.events[0].status, "UNMEASURED");
  assert.deepEqual(
    result.events.slice(1).map((item) => item.errorCode),
    ["DECODE_FAILED", "UNSUPPORTED_FORMAT", "PAYLOAD_TOO_LARGE"],
  );
  for (const extra of [
    { status: "PASS" },
    { status: "UNMEASURED", markerRecovered: true },
  ]) {
    assert.throws(() =>
      reducePipelineEvidence(cases, runId, [
        { ...base, assetKey: cases[0].assetKey, ...extra },
      ]),
    );
  }
  const truncated = reducePipelineEvidence(manifest, runId, [
    { ...event(0, "embedded"), status: "FAIL", errorCode: "TRUNCATED" },
  ]);
  assert.deepEqual(truncated.deliveredIds, []);
  assert.equal(truncated.events[0].errorCode, "TRUNCATED");
});

test("사건 schema와 로그 허용 목록은 원문, 경로, 비용 주장을 받지 않는다", () => {
  for (const bad of [
    { schemaVersion: 2 },
    { durationMs: -1 },
    { byteLength: 1.5 },
    { sha256: "bad" },
    { errorCode: "원문" },
    { markerRecovered: "yes" },
    { status: "MAYBE" },
    { scope: "REAL" },
    { path: "private" },
    { ocr: "사진" },
    { base64: "bytes" },
    { cost: null },
    { assetKey: null },
    { runId: 123 },
    { sha256: ["a".repeat(64)] },
  ]) {
    assert.throws(() => validatePipelineEvidence({ ...event(), ...bad }));
  }
  const log = pipelineLog({ ...event(), durationMs: 12 });
  assert.deepEqual(
    Object.keys(log).sort(),
    [
      "assetKey",
      "byteLength",
      "durationMs",
      "sha256",
      "stage",
      "status",
    ].sort(),
  );
  assert.ok(!JSON.stringify(log).includes("사진"));
});
