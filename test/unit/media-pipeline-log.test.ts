import assert from "node:assert/strict";
import test from "node:test";
import {
  backendEvidence,
  parseBackendPipelineEvents,
} from "../support/media-pipeline-log.ts";

const DRAFT = "0b9f6c1e-7d1a-4c2e-9a55-2f3c4d5e6f70";
const embedded = {
  schemaVersion: 1,
  stage: "embedded",
  conversationId: 7,
  count: 2,
  assets: [
    { assetId: "11", position: 0 },
    { assetId: "13", position: 1 },
  ],
  missing: ["12"],
};
const referenced = {
  schemaVersion: 1,
  stage: "draftReferenced",
  conversationId: 7,
  draftId: DRAFT,
  revision: 2,
  count: 2,
  assets: [
    { assetId: "11", position: 1 },
    { assetId: "13", position: 3 },
  ],
};
const line = (value: unknown) =>
  `2026-10-12T00:00:00 INFO 1 --- [x] c.b.a.c.a.MediaPipelineEvents : media-pipeline ${JSON.stringify(value)}`;

test("Control Plane 로그에서 단계 사건만 골라 읽는다", () => {
  const events = parseBackendPipelineEvents(
    ["다른 로그 {\"text\":\"본문\"}", line(embedded), line(referenced), ""].join("\n"),
  );
  assert.deepEqual(events, [embedded, referenced]);
});

test("허용 목록 밖의 칸, 글, 잘못된 번호와 자리는 거절한다", () => {
  const broken: unknown[] = [
    { ...embedded, caption: "사진 설명" },
    { ...referenced, title: "원고 제목" },
    { ...embedded, assets: [{ assetId: "11", position: 0, text: "x" }], count: 1 },
    { ...embedded, assets: [{ assetId: "abc", position: 0 }], count: 1 },
    { ...embedded, count: 3 },
    { ...embedded, assets: [embedded.assets[1], embedded.assets[0]] },
    { ...embedded, missing: ["11"] },
    { ...referenced, draftId: "../etc" },
    { ...referenced, revision: 0 },
    { ...referenced, missing: [] },
    { ...embedded, stage: "saved" },
    { ...embedded, schemaVersion: 2 },
  ];
  for (const value of broken) {
    assert.throws(() => parseBackendPipelineEvents(line(value)), /BACKEND_EVENT_/);
  }
  assert.throws(
    () => parseBackendPipelineEvents("media-pipeline {잘린"),
    /BACKEND_EVENT_INVALID/,
  );
});

test("첨부 번호를 manifest 키로 잇고 싣지 못한 사진은 실패로 남긴다", () => {
  const keys = new Map([
    ["11", "synthetic-1"],
    ["12", "synthetic-2"],
    ["13", "synthetic-3"],
  ]);
  const [event] = parseBackendPipelineEvents(line(embedded));
  const evidence = backendEvidence(event!, "run-1", keys, "FAKE_HERMES");
  assert.deepEqual(
    evidence.map((item) => [item.assetKey, item.stage, item.status]),
    [
      ["synthetic-1", "embedded", "PASS"],
      ["synthetic-3", "embedded", "PASS"],
      ["synthetic-2", "embedded", "FAIL"],
    ],
  );
  assert.throws(
    () => backendEvidence(event!, "run-1", new Map([["11", "synthetic-1"]]), "FAKE_HERMES"),
    /BACKEND_EVENT_FOREIGN_ID/,
  );
});
