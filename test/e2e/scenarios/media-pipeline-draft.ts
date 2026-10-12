/** 사진 30장 흐름에서 Control Plane 이 남긴 본문 없는 단계 사건을 모아 단계별 증거로 판정한다. */
import { randomUUID } from "node:crypto";
import type { MediaFixture } from "../../support/media-pipeline-fixture.ts";
import {
  reducePipelineEvidence,
  type PipelineEvidenceV1,
} from "../../support/media-pipeline-evidence.ts";
import {
  backendEvidence,
  parseBackendPipelineEvents,
  type BackendPipelineEvent,
} from "../../support/media-pipeline-log.ts";
import { expect, fail, step, type Context } from "../harness.ts";
import { callTool, contextFor, type ToolResult } from "../delegation-support.ts";

const WRITE = "content_draft_write";
type DraftView = { draft: { draftId: string; revision: number } };

function draft(result: ToolResult): DraftView {
  expect(!result.isError, `원고 도구가 실패했다: ${result.text}`);
  const begin = "\n<external-data>\n";
  return JSON.parse(
    result.text.slice(
      result.text.indexOf(begin) + begin.length,
      result.text.lastIndexOf("</external-data>"),
    ),
  ) as DraftView;
}

/** 로그는 프로세스 출력을 파일로 옮겨 쓰므로 응답보다 늦게 닿을 수 있다. 조건을 만족할 때까지 짧게 다시 읽는다. */
async function waitForEvent(
  context: Context,
  match: (event: BackendPipelineEvent) => boolean,
  what: string,
): Promise<BackendPipelineEvent> {
  for (let attempt = 0; attempt < 100; attempt++) {
    const found = parseBackendPipelineEvents(await context.readBackendLog()).filter(match);
    if (found.length > 1) fail(`${what} 사건이 중복됐다`);
    if (found.length === 1) return found[0]!;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  fail(`${what} 사건이 로그에 없다`);
}

/**
 * 실은 사진 사건과 원고 참조 사건을 실제 Control Plane 로그에서 모은다. 첫 판은 30장을 모두 참조하고,
 * 둘째 판은 가운데 사진 한 장을 빼서 빠진 사진이 포함으로 판정되지 않는지 본다.
 */
export async function checkPipelineEvidence(
  context: Context,
  token: string,
  session: string,
  fixtures: readonly MediaFixture[],
  sent: readonly MediaFixture[],
  sentIds: readonly number[],
): Promise<void> {
  step("Control Plane 단계 사건으로 실은 사진과 원고 참조를 집계한다");
  const assetKeys = new Map(
    sent.map((fixture, position) => [String(sentIds[position]), fixture.assetKey]),
  );
  const ours = (event: BackendPipelineEvent) =>
    [...event.assets.map((asset) => asset.assetId), ...(event.missing ?? [])].some((id) =>
      assetKeys.has(id),
    );
  const embedded = await waitForEvent(
    context,
    (event) => event.stage === "embedded" && ours(event),
    "실은 사진",
  );
  expect(
    embedded.count === 30 &&
      embedded.missing!.length === 0 &&
      embedded.assets.every((asset, position) => asset.assetId === String(sentIds[position])),
    "실은 사진 사건의 첨부 순서가 보낸 순서와 다르다",
  );
  expect(
    context.hermes.lastSubmittedImages().length === embedded.count,
    "실은 사진 사건과 대역이 받은 이미지 수가 다르다",
  );
  const blocks = (ids: readonly number[]) => [
    { type: "paragraph", text: "합성 원고" },
    ...ids.map((id) => ({ type: "image", assetId: String(id) })),
  ];
  const first = draft(
    await callTool(
      context,
      token,
      WRITE,
      {
        requestId: randomUUID(),
        draft: { title: "합성 사진 원고", category: "분류", tags: [], blocks: blocks(sentIds) },
      },
      contextFor(token, WRITE, session),
    ),
  ).draft;
  const removed = sentIds[15]!;
  const kept = sentIds.filter((id) => id !== removed);
  const second = draft(
    await callTool(
      context,
      token,
      WRITE,
      {
        draftId: first.draftId,
        expectedRevision: 1,
        draft: { title: "합성 사진 원고", category: "분류", tags: [], blocks: blocks(kept) },
      },
      contextFor(token, WRITE, session),
    ),
  ).draft;
  expect(second.revision === 2, "가운데 사진을 뺀 원고가 새 판을 만들지 못했다");
  const referenced = async (revision: number) =>
    waitForEvent(
      context,
      (event) =>
        event.stage === "draftReferenced" &&
        event.draftId === first.draftId &&
        event.revision === revision,
      `원고 ${revision}판 참조`,
    );
  const full = await referenced(1);
  const partial = await referenced(2);
  expect(
    full.assets.every(
      (asset, index) =>
        asset.assetId === String(sentIds[index]) && asset.position === index + 1,
    ),
    "원고 참조 사건의 첨부·블록 자리가 원고와 다르다",
  );
  const judge = (runId: string, draftEvent: BackendPipelineEvent) => {
    const uploaded = sent.map(
      (fixture): PipelineEvidenceV1 => ({
        schemaVersion: 1,
        runId,
        assetKey: fixture.assetKey,
        stage: "uploaded",
        status: "PASS",
        scope: "FAKE_HERMES",
        byteLength: fixture.byteLength,
        sha256: fixture.bytesSha256!,
      }),
    );
    return reducePipelineEvidence(fixtures, runId, [
      ...uploaded,
      ...backendEvidence(embedded, runId, assetKeys, "FAKE_HERMES"),
      ...backendEvidence(draftEvent, runId, assetKeys, "FAKE_HERMES"),
    ]);
  };
  const whole = judge("media30-full", full);
  const missing = judge("media30-middle-removed", partial);
  const removedKey = assetKeys.get(String(removed))!;
  expect(
    whole.deliveredIds.length === 30 &&
      whole.includedIds.length === 30 &&
      whole.omittedIds.length === 0,
    "30장 전달·참조 집계가 다르다",
  );
  expect(
    missing.includedIds.length === 29 &&
      missing.omittedIds.length === 1 &&
      missing.omittedIds[0] === removedKey,
    "가운데 사진을 뺀 원고를 전체 참조로 판정했다",
  );
  expect(
    whole.observation === "UNMEASURED" && whole.realProvider === "UNMEASURED",
    "전달과 참조 사건만으로 판독 성공을 만들었다",
  );
  const passed = (stage: string) =>
    whole.events.filter((event) => event.stage === stage && event.status === "PASS").length;
  console.log(
    JSON.stringify({
      uploaded: passed("uploaded"),
      embedded: passed("embedded"),
      draftReferenced: passed("draftReferenced"),
      middleRemovedOmitted: missing.omittedIds,
      actuallyObserved: whole.observation,
      validatedOriginals: "UNMEASURED",
      editorUploaded: "UNMEASURED",
      saved: "UNMEASURED",
      realProvider: whole.realProvider,
    }),
  );
}
