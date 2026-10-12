import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  createSpecialMediaCases,
  type SpecialMediaCase,
} from "../support/media-pipeline-fixture.ts";
import {
  fakeProviderRoutes,
  judgeObservationClaims,
  type AssetTraits,
  type RecordedObservation,
  type VisionRoute,
} from "../support/media-observation-claims.ts";
import {
  backendEvidence,
  parseBackendPipelineEvents,
} from "../support/media-pipeline-log.ts";

const dir = mkdtempSync(join(tmpdir(), "media-special-"));
let cases: SpecialMediaCase[];
test.before(() => {
  cases = createSpecialMediaCases(dir);
});
test.after(() => rmSync(dir, { recursive: true, force: true }));
const byKey = (key: string) => cases.find((item) => item.assetKey === key)!;
const traitsOf = (items: readonly SpecialMediaCase[]): AssetTraits[] =>
  items.map((item) => ({
    assetKey: item.assetKey,
    bytesSha256: item.bytesSha256,
    expectedMarker: item.expectedMarker,
    text:
      item.textPixels === 0
        ? "NONE"
        : item.menuTextOriginal && !item.menuTextPrepared
          ? "ORIGINAL_ONLY"
          : "LABEL",
  }));
const observation = (
  assetKey: string,
  extra: Partial<RecordedObservation> = {},
): RecordedObservation => ({
  assetKey,
  status: "NEEDS_REVIEW",
  coverage: "OVERVIEW",
  claims: [{ kind: "VISUAL", confidence: "UNCERTAIN" }],
  ...extra,
});
const all = (keys: readonly string[], route: VisionRoute) =>
  new Map(keys.map((key) => [key, route] as const));

test("특수 합성 사례를 다시 생성해도 바이트와 측정 결과가 같다", () => {
  assert.deepEqual(createSpecialMediaCases(join(dir, "repeat")), cases);
});

test("같은 디렉터리에 특수 합성 사례를 다시 생성해도 바이트와 측정 결과가 같다", () => {
  assert.deepEqual(createSpecialMediaCases(dir), cases);
});

test("고해상도 간판은 표식과 제목이 사본에 남고 1px 메뉴 글씨는 원본에서만 읽힌다", () => {
  const sign = byKey("sign-hires");
  assert.deepEqual(
    [sign.width, sign.height, sign.preparedWidth, sign.preparedHeight],
    [4032, 3024, 1600, 1200],
  );
  assert.equal(sign.recoveredId, 40);
  assert.equal(sign.preparedRecoveredId, 40);
  assert.equal(sign.labelTextOriginal, true);
  assert.equal(sign.labelTextPrepared, true);
  assert.equal(sign.menuTextOriginal, true);
  assert.equal(sign.menuTextPrepared, false);
});

test("풍경은 표식 밖에 글씨 화소가 없고 표식은 사본에서도 회수된다", () => {
  const landscape = byKey("landscape");
  assert.equal(landscape.textPixels, 0);
  assert.equal(landscape.recoveredId, 41);
  assert.equal(landscape.preparedRecoveredId, 41);
  assert.equal(landscape.labelTextOriginal, false);
});

test("같은 사진은 바이트까지 같고 거의 같은 사진은 표식만 같다", () => {
  const [a, b, nearA, nearB] = ["identical-a", "identical-b", "near-a", "near-b"].map(byKey);
  assert.equal(a!.bytesSha256, b!.bytesSha256);
  assert.equal(a!.recoveredId, b!.recoveredId);
  assert.notEqual(nearA!.bytesSha256, nearB!.bytesSha256);
  assert.equal(nearA!.recoveredId, 44);
  assert.equal(nearB!.recoveredId, 44);
  const verdict = judgeObservationClaims(
    traitsOf(cases),
    all(cases.map((item) => item.assetKey), "NATIVE"),
    [],
  );
  const identity = Object.fromEntries(verdict.rows.map((row) => [row.assetKey, row.pixelIdentity]));
  assert.deepEqual(identity, {
    "sign-hires": "UNIQUE",
    landscape: "UNIQUE",
    "identical-a": "AMBIGUOUS",
    "identical-b": "AMBIGUOUS",
    "near-a": "SHA_ONLY",
    "near-b": "SHA_ONLY",
  });
});

test("사본만 본 관찰은 작은 메뉴 글씨와 풍경의 글씨를 확인했다고 주장하지 못한다", () => {
  const traits = traitsOf(cases);
  const routes = all(cases.map((item) => item.assetKey), "NATIVE");
  const ocr = { kind: "OCR", confidence: "CONFIRMED" } as const;
  assert.throws(
    () =>
      judgeObservationClaims(traits, routes, [observation("sign-hires", { claims: [ocr] })]),
    /OVERCLAIM_SMALL_TEXT/,
  );
  for (const coverage of ["ORIGINAL", "CROP"] as const) {
    const observations = [observation("sign-hires", { coverage, claims: [ocr] })];
    assert.throws(
      () => judgeObservationClaims(traits, routes, observations),
      /OVERCLAIM_SMALL_TEXT/,
    );
    assert.throws(
      () => judgeObservationClaims(traits, routes, observations, new Map([["landscape", coverage]])),
      /OVERCLAIM_SMALL_TEXT/,
    );
    assert.throws(
      () => judgeObservationClaims(traits, routes, observations,
        new Map([["sign-hires", coverage === "CROP" ? "ORIGINAL" : "CROP"]])),
      /OVERCLAIM_SMALL_TEXT/,
    );
    assert.doesNotThrow(() =>
      judgeObservationClaims(traits, routes, observations, new Map([["sign-hires", coverage]])),
    );
  }
  assert.throws(
    () =>
      judgeObservationClaims(traits, routes, [
        observation("landscape", { coverage: "ORIGINAL", claims: [ocr] }),
      ]),
    /OVERCLAIM_TEXT/,
  );
  assert.doesNotThrow(() =>
    judgeObservationClaims(traits, routes, [
      observation("landscape", { claims: [{ kind: "VISUAL", confidence: "CONFIRMED" }] }),
    ]),
  );
});

/** 경로 판정용 단위 입력이다. 로그 줄을 직접 만들며 실제 Control Plane 이나 대역 Hermes 를 실행하지 않는다. */
function thirty(missingCount: number) {
  const traits: AssetTraits[] = Array.from({ length: 30 }, (_, index) => ({
    assetKey: `synthetic-${index + 1}`,
    bytesSha256: (index + 1).toString(16).padStart(64, "0"),
    expectedMarker: index + 1,
    text: "LABEL",
  }));
  const ids = traits.map((_, index) => String(101 + index));
  const embeddedIds = ids.slice(0, 30 - missingCount);
  const line = `media-pipeline ${JSON.stringify({
    schemaVersion: 1,
    stage: "embedded",
    conversationId: 7,
    count: embeddedIds.length,
    assets: embeddedIds.map((assetId, position) => ({ assetId, position })),
    missing: ids.slice(30 - missingCount),
  })}`;
  const keys = new Map(ids.map((id, index) => [id, traits[index]!.assetKey]));
  const evidence = backendEvidence(parseBackendPipelineEvents(line)[0]!, "run-1", keys, "FAKE_HERMES");
  return {
    traits,
    embedded: evidence.filter((item) => item.status === "PASS").map((item) => item.assetKey),
    missing: evidence.filter((item) => item.status === "FAIL").map((item) => item.assetKey),
  };
}

test("vision 지원 provider 는 30장을 모두 받은 것으로 판정한다", () => {
  const { traits, embedded, missing } = thirty(0);
  const verdict = judgeObservationClaims(
    traits,
    fakeProviderRoutes(embedded, missing, "vision"),
    embedded.map((key) => observation(key)),
  );
  assert.equal(verdict.delivery, "NATIVE");
  assert.ok(verdict.rows.every((row) => row.verdict === "IMAGE_RECEIVED"));
});

test("이미지 수신 판정은 관찰 누락이나 판독 실패를 성공으로 바꾸지 않는다", () => {
  const { traits, embedded } = thirty(0);
  const routes = all(embedded, "NATIVE");
  for (const observations of [[], [observation(embedded[0]!, {
    status: "FAILED", coverage: null, claims: [],
  })]]) {
    const verdict = judgeObservationClaims(traits, routes, observations);
    assert.equal(verdict.delivery, "NATIVE");
    assert.ok(verdict.rows.every((row) => row.verdict === "IMAGE_RECEIVED"));
  }
});

test("vision 미지원 provider 의 보조 설명은 fallback 이고 확인했다는 주장을 거절한다", () => {
  const { traits, embedded, missing } = thirty(0);
  const routes = fakeProviderRoutes(embedded, missing, "unsupported");
  const honest = judgeObservationClaims(traits, routes, embedded.map((key) => observation(key)));
  assert.equal(honest.delivery, "FALLBACK");
  assert.ok(honest.rows.every((row) => row.verdict === "FALLBACK"));
  for (const overclaim of [
    { status: "SUCCEEDED" as const },
    { coverage: "ORIGINAL" as const },
    { claims: [{ kind: "VISUAL" as const, confidence: "CONFIRMED" as const }] },
  ]) {
    assert.throws(
      () => judgeObservationClaims(traits, routes, [observation(embedded[0]!, overclaim)]),
      /OVERCLAIM_FALLBACK/,
    );
  }
});

test("이미지를 뗀 재시도는 실패이고 읽었다는 관찰을 거절한다", () => {
  const { traits, embedded, missing } = thirty(0);
  const routes = fakeProviderRoutes(embedded, missing, "rejecting");
  assert.throws(
    () => judgeObservationClaims(traits, routes, [observation(embedded[0]!)]),
    /OVERCLAIM_NOT_RECEIVED/,
  );
  const failed = judgeObservationClaims(
    traits,
    routes,
    embedded.map((key) => ({ assetKey: key, status: "FAILED", coverage: null, claims: [] })),
  );
  assert.equal(failed.delivery, "FAILED");
  for (const route of ["STRIPPED", "NOT_EMBEDDED"] as const) {
    for (const extra of [
      { coverage: "OVERVIEW" as const, claims: [] },
      { coverage: null, claims: [{ kind: "VISUAL" as const, confidence: "UNCERTAIN" as const }] },
      { coverage: null, claims: [{ kind: "OCR" as const, confidence: "CONFIRMED" as const }] },
    ]) {
      assert.throws(
        () => judgeObservationClaims(traits, all(embedded, route), [
          observation(embedded[0]!, { status: "FAILED", ...extra }),
        ]),
        /OVERCLAIM_NOT_RECEIVED/,
      );
    }
  }
});

test("일부를 싣지 못하면 provider 가 vision 을 지원하지 않아도 fallback 이 아니라 부분 실패다", () => {
  const { traits, embedded, missing } = thirty(5);
  assert.equal(missing.length, 5);
  for (const provider of ["vision", "unsupported"] as const) {
    const routes = fakeProviderRoutes(embedded, missing, provider);
    const verdict = judgeObservationClaims(traits, routes, embedded.map((key) => observation(key)));
    assert.equal(verdict.delivery, "PARTIAL_FAILURE");
    assert.deepEqual(
      verdict.rows.filter((row) => row.verdict === "NOT_RECEIVED").map((row) => row.assetKey),
      missing,
    );
    assert.throws(
      () => judgeObservationClaims(traits, routes, [observation(missing[0]!)]),
      /OVERCLAIM_NOT_RECEIVED/,
    );
  }
});

test("경로를 관측하지 못한 실제 흐름은 모델이 성공을 주장해도 미측정이다", () => {
  const { traits, embedded } = thirty(0);
  const verdict = judgeObservationClaims(
    traits,
    all(traits.map((trait) => trait.assetKey), "UNKNOWN"),
    embedded.map((key) => observation(key, { status: "SUCCEEDED", claims: [] })),
  );
  assert.equal(verdict.delivery, "UNMEASURED");
  assert.ok(verdict.rows.every((row) => row.verdict === "UNMEASURED"));
});

test("경로가 없거나 같은 사진의 관찰이 두 번이면 거절한다", () => {
  const { traits, embedded } = thirty(0);
  assert.throws(() => judgeObservationClaims(traits, new Map(), []), /ROUTE_MISSING/);
  assert.throws(
    () =>
      judgeObservationClaims(traits, all(embedded, "NATIVE"), [
        observation(embedded[0]!),
        observation(embedded[0]!),
      ]),
    /OBSERVATION_ASSET_INVALID/,
  );
  assert.throws(() => fakeProviderRoutes(["a"], ["a"], "vision"), /ROUTE_CONFLICT/);
});
