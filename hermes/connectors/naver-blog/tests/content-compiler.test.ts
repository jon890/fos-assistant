import { afterAll, beforeAll, expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { compileContentDraft, ContentDraftError } from "../src/content-compiler.ts";
import { canonicalDraftV1Schema, compiledSnapshotV1Schema, resolvedAssetSchema,
  type CanonicalBlock, type CanonicalDraftV1, type CompiledSnapshotV1, type ResolvedAsset,
} from "../src/content-draft.ts";
import { parseBody, validateDraft } from "../src/draft.ts";
import { renderDraft } from "../src/render.ts";
import golden from "./fixtures/content-snapshot-v1.json";

let attachmentDir: string;
let photoDir: string;
beforeAll(async () => {
  attachmentDir = await realpath(await mkdtemp(join(tmpdir(), "content-compiler-")));
  photoDir = join(attachmentDir, "synthetic");
  await mkdir(photoDir);
  await writeFile(join(photoDir, "101.jpg"), Uint8Array.from([0xff, 0xd8, 0xff, 0xe0]));
});
afterAll(async () => { await rm(attachmentDir, { recursive: true, force: true }); });

const draft = (blocks: CanonicalBlock[] = [], extra: Partial<CanonicalDraftV1> = {}): CanonicalDraftV1 => ({
  schemaVersion: 1, draftId: "11111111-1111-4111-8111-111111111111", revision: 1,
  conversationId: "22222222-2222-4222-8222-222222222222", title: "사진 원고", category: "일상",
  tags: ["사진", "일상"], blocks, ...extra,
});
const asset = (assetId = "101", extra: Partial<ResolvedAsset> = {}): ResolvedAsset => ({
  assetId, sourceFingerprint: "a".repeat(64), fileName: `${assetId}.jpg`, photoDir,
  ordinal: Number(assetId), ...extra,
});
const image = (assetId = "101"): CanonicalBlock => ({ type: "image", assetId });
const paragraph = (text: string): CanonicalBlock => ({ type: "paragraph", text });
function expectError(run: () => unknown, code: ContentDraftError["code"]) {
  let caught: unknown;
  try { run(); } catch (error) { caught = error; }
  expect(caught).toBeInstanceOf(ContentDraftError);
  expect((caught as ContentDraftError).code).toBe(code);
  expect((caught as Error).message).toBe(code);
}
const sha = (value: string) => createHash("sha256").update(value, "utf8").digest("hex");
const payloadJson = (snapshot: CompiledSnapshotV1) => JSON.stringify([
  snapshot.payload.title, snapshot.payload.category, snapshot.payload.tags,
  snapshot.payload.body, snapshot.payload.photo_dir ?? null,
]);
const snapshotJson = (snapshot: CompiledSnapshotV1) => JSON.stringify([
  snapshot.schemaVersion, snapshot.compilerVersion, snapshot.draftId, snapshot.revision,
  snapshot.canonicalBlocks,
  snapshot.assetManifest.map((entry) => [entry.position, entry.assetId, entry.sourceFingerprint, entry.fileName]),
  sha(payloadJson(snapshot)), Object.entries(snapshot.photoNotes)
    .map(([number, note]) => [Number(number), note]).sort((a, b) => Number(a[0]) - Number(b[0])),
]);

test("30장과 문단을 교차 배치하며 문서 순서와 사진 자리 번호를 보존한다", () => {
  const assets = Array.from({ length: 30 }, (_, index) => asset(String(index + 1), {
    ordinal: 30 - index, observationNote: `${index + 1}번 관찰`,
  }));
  const blocks = assets.flatMap((entry) => [paragraph(`${entry.assetId}번 앞 문단`), image(entry.assetId)]);
  const snapshot = compileContentDraft(draft(blocks), [...assets].reverse());
  expect(parseBody(snapshot.payload.body)).toEqual(assets.flatMap((entry, index) => [
    { type: "text", line: `${entry.assetId}번 앞 문단` },
    { type: "image", number: index + 1, file: entry.fileName },
  ]));
  expect(validateDraft(snapshot.payload)).toEqual([]);
  expect(snapshot.assetManifest.map((entry) => entry.assetId)).toEqual(assets.map((entry) => entry.assetId));
  expect(snapshot.assetManifest.map((entry) => entry.position)).toEqual(assets.map((_, index) => index * 2 + 1));
  expect(snapshot.photoNotes["10"]).toBe("10번 관찰");
  expect(snapshot.photoNotes["30"]).toBe("30번 관찰");
  expect(snapshot.snapshotHash).toBe(sha(snapshotJson(snapshot)));
  expect(compileContentDraft(draft(blocks), assets)).toEqual(snapshot);
});

test("같은 asset의 반복 자리와 포함 subset을 manifest로 구별한다", () => {
  const snapshot = compileContentDraft(draft([image(), paragraph("사이"), image()]), [asset(), asset("102")]);
  expect(snapshot.assetManifest.map((entry) => entry.assetId)).toEqual(["101", "101"]);
  expect(snapshot.assetManifest.map((entry) => entry.position)).toEqual([0, 2]);
  expect(new Set(snapshot.assetManifest.map((entry) => entry.assetId)).size).toBe(1);
  expect(snapshot.payload.body).toBe("[사진 1: 101.jpg]\n사이\n[사진 2: 101.jpg]");
  expect(snapshot.payload.photo_dir).toBe(photoDir);
  expect(compileContentDraft(draft([image(), paragraph("사이"), image()]), [asset()])).toEqual(snapshot);
});

test("caption은 실제 문단이고 관찰 설명은 미리보기 안내에만 남는다", async () => {
  const snapshot = compileContentDraft(draft([
    { type: "image", assetId: "101", caption: "실제로 저장할 설명", observationRef: { id: "901", revision: 2 } },
  ]), [asset("101", { observationNote: "관찰에서 가져온 안내" })]);
  expect(parseBody(snapshot.payload.body)).toEqual([
    { type: "image", number: 1, file: "101.jpg" }, { type: "text", line: "실제로 저장할 설명" },
  ]);
  expect(snapshot.photoNotes).toEqual({ "1": "관찰에서 가져온 안내" });
  expect(snapshot.payload.body).not.toContain("관찰에서 가져온 안내");
  const rendered = await renderDraft({ ...snapshot.payload, photo_notes: snapshot.photoNotes,
    artifact_path: "index.html" }, attachmentDir);
  expect(rendered.problems).toEqual([]);
  expect(rendered.html).toContain('<figcaption><span class="label">1번째 사진</span> 관찰에서 가져온 안내</figcaption>');
  expect(rendered.html).toContain('<p>실제로 저장할 설명</p>');
});

test("빈 caption은 빈 문단이고 사진 없는 원고는 photo_dir를 생략한다", () => {
  const snapshot = compileContentDraft(draft([{ type: "image", assetId: "101", caption: "" }]), [asset()]);
  expect(snapshot.payload.body).toBe("[사진 1: 101.jpg]\n");
  expect(snapshot.canonicalBlocks).toEqual([["image", "101", "", null]]);
  const textOnly = compileContentDraft(draft([paragraph("")]), [asset()]);
  expect(Object.hasOwn(textOnly.payload, "photo_dir")).toBe(false);
  expect(textOnly.assetManifest).toEqual([]);
  expect(textOnly.photoNotes).toEqual({});
  expect(compileContentDraft(draft(), []).canonicalBlocks).toEqual([]);
});

test("snapshot 전체는 입력 변경과 직접 변경에서 독립된 불변 JSON 값이다", () => {
  const input = draft([{ type: "image", assetId: "101", observationRef: { id: "901", revision: 1 } }]);
  const assets = [asset("101", { observationNote: "안내" })];
  const snapshot = compileContentDraft(input, assets);
  const original = JSON.stringify(snapshot);
  input.tags.push("수정");
  input.blocks.length = 0;
  assets[0]!.observationNote = "바뀐 안내";
  expect(JSON.stringify(snapshot)).toBe(original);
  function expectFrozen(value: unknown) {
    if (value && typeof value === "object") {
      expect(Object.isFrozen(value)).toBe(true);
      Object.values(value).forEach(expectFrozen);
    }
  }
  expectFrozen(snapshot);
  expect(Object.isFrozen(input)).toBe(false);
  expect(() => snapshot.payload.tags.push("변조")).toThrow();
  expect(compiledSnapshotV1Schema.safeParse(JSON.parse(original)).success).toBe(true);
});

test("공유 golden vector는 compact JSON과 UTF-8 바이트와 두 SHA-256을 고정한다", () => {
  const snapshot = compileContentDraft(canonicalDraftV1Schema.parse(golden.draft),
    resolvedAssetSchema.array().parse(golden.assets));
  expect(snapshot).toEqual(compiledSnapshotV1Schema.parse(golden.snapshot));
  expect(snapshot.assetManifest.map((entry) => entry.position)).toEqual([1, 4]);
  expect(parseBody(snapshot.payload.body).filter((block) => block.type === "image")
    .map((block) => block.number)).toEqual([1, 2]);
  expect(payloadJson(snapshot)).toBe(golden.payloadCompactJson);
  expect(snapshotJson(snapshot)).toBe(golden.snapshotCompactJson);
  expect(Buffer.from(payloadJson(snapshot), "utf8").toString("hex")).toBe(golden.payloadUtf8Hex);
  expect(Buffer.from(snapshotJson(snapshot), "utf8").toString("hex")).toBe(golden.snapshotUtf8Hex);
  expect(sha(golden.payloadCompactJson)).toBe(golden.snapshot.payloadFingerprint);
  expect(sha(golden.snapshotCompactJson)).toBe(golden.snapshot.snapshotHash);
});

test.each([
  "[사진 1: 101.jpg]", "[사진 999: invalid name]", "[스티커: ogq_abc-1]",
  "[지도: 가상 가게 | 예시 주소]", "[기존 사진 1]", "[기존 스티커 2]", "[기존 지도 3]",
  "[기존 구성요소 1: table]",
])("문단과 caption의 예약 줄 %s를 명시적인 코드로 거절한다", (text) => {
  expectError(() => compileContentDraft(draft([paragraph(text)]), []), "CONTENT_DRAFT_RESERVED_LINE");
  expectError(() => compileContentDraft(draft([{ type: "image", assetId: "101", caption: text }]), [asset()]),
    "CONTENT_DRAFT_RESERVED_LINE");
});
test.each(["[사진 0: 101.jpg]", "[사진 1: a/b.jpg]", " [사진 1: 101.jpg]", "[스티커: !]",
  "[지도: 가게|주소]", "[기존 사진 0]", "예약 줄 [사진 1: 101.jpg] 옆의 문장",
])("정확한 예약 줄이 아닌 %s는 원문 그대로 문단으로 남긴다", (text) => {
  const snapshot = compileContentDraft(draft([paragraph(text)]), []);
  expect(parseBody(snapshot.payload.body)).toEqual([{ type: "text", line: text }]);
  expect(validateDraft(snapshot.payload)).toEqual([]);
});
test.each(["앞\n뒤", "앞\r뒤", "앞\r\n뒤"])("문단과 caption 안의 CR/LF %s를 거절한다", (text) => {
  expectError(() => compileContentDraft(draft([paragraph(text)]), []), "CONTENT_DRAFT_INVALID");
  expectError(() => compileContentDraft(draft([{ type: "image", assetId: "101", caption: text }]), [asset()]),
    "CONTENT_DRAFT_INVALID");
});

test("Unicode code point 상한과 줄바꿈을 포함한 전체 body 상한을 검사한다", () => {
  const atLimit = compileContentDraft(draft([paragraph("😀".repeat(20_000))], {
    title: "😀".repeat(100), category: "😀".repeat(50), tags: ["😀".repeat(30)],
  }), []);
  expect(validateDraft(atLimit.payload)).toEqual([]);
  expectError(() => compileContentDraft(draft([paragraph("😀".repeat(20_001))]), []), "CONTENT_DRAFT_INVALID");
  expectError(() => compileContentDraft(draft([paragraph("가".repeat(20_000)), paragraph("")]), []),
    "CONTENT_DRAFT_INVALID");
  const captionInput = draft([{ type: "image", assetId: "101", caption: "😀".repeat(300) }]);
  expect(validateDraft(compileContentDraft(captionInput, [asset("101", { observationNote: "😀".repeat(300) })]).payload))
    .toEqual([]);
});

const invalidDraftFields: Partial<CanonicalDraftV1>[] = [
  { title: "" }, { title: "😀".repeat(101) }, { category: "" }, { category: "😀".repeat(51) },
  { tags: [""] }, { tags: ["😀".repeat(31)] }, { tags: ["#사진"] }, { tags: ["사진,원고"] },
  { tags: ["Photo", " photo "] }, { tags: Array.from({ length: 31 }, (_, index) => `태그${index}`) },
  { revision: 0 }, { revision: 1.5 }, { revision: Number.MAX_SAFE_INTEGER + 1 },
  { draftId: "잘못된 UUID" }, { conversationId: "잘못된 UUID" },
];
test.each(invalidDraftFields)("원고의 상한·태그 중복·식별자 오류 %j를 거절한다", (extra) => {
  expectError(() => compileContentDraft(draft([], extra), []), "CONTENT_DRAFT_INVALID");
});

test.each([
  { type: "image", assetId: "abc" }, { type: "image", assetId: "101", caption: "😀".repeat(301) },
  { type: "image", assetId: "101\n" },
  { type: "image", assetId: "101", observationRef: { id: "x", revision: 1 } },
  { type: "image", assetId: "101", observationRef: { id: "901", revision: 0 } },
  { type: "sticker", code: "!" }, { type: "sticker", code: "a".repeat(65) },
  { type: "sticker", code: "a\n" },
  { type: "map", name: "", address: "주소" }, { type: "map", name: "가게", address: "" },
  { type: "map", name: "가|게", address: "주소" }, { type: "map", name: "가게]", address: "주소" },
  { type: "map", name: "가게", address: "주\n소" }, { type: "map", name: "가게", address: "주소]" },
  { type: "map", name: "😀".repeat(201), address: "주소" }, { type: "map", name: "가게", address: "😀".repeat(201) },
])("블록의 잘못된 필드 %j를 거절한다", (block) => {
  expect(canonicalDraftV1Schema.safeParse(draft([block as CanonicalBlock])).success).toBe(false);
});

test("블록·사진·스티커·지도 상한까지 기존 DSL로 변환하고 하나 초과는 거절한다", () => {
  const cases: [CanonicalBlock, number, ResolvedAsset[]][] = [
    [image(), 50, [asset()]], [{ type: "sticker", code: "a_1-2" }, 30, []],
    [{ type: "map", name: "😀".repeat(200), address: "😀".repeat(200) }, 30, []],
    [paragraph(""), 500, []],
  ];
  for (const [block, max, assets] of cases) {
    const snapshot = compileContentDraft(draft(Array.from({ length: max }, () => block)), assets);
    expect(validateDraft(snapshot.payload)).toEqual([]);
    expect(snapshot.canonicalBlocks).toHaveLength(max);
    expectError(() => compileContentDraft(draft(Array.from({ length: max + 1 }, () => block)), assets),
      "CONTENT_DRAFT_INVALID");
  }
  expect(compileContentDraft(draft([], { tags: Array.from({ length: 30 }, (_, index) => `태그${index}`) }), [])
    .payload.tags).toHaveLength(30);
});

test.each([
  { fileName: "../101.jpg" }, { fileName: "101..jpg" }, { fileName: "101.txt" },
  { fileName: "101.small.jpg" }, { fileName: "101.SMALL.JPG" }, { fileName: "101]\n.jpg" },
  { fileName: "101.jpg\n" }, { sourceFingerprint: `${"a".repeat(64)}\n` }, { assetId: "101\n" },
  { photoDir: "relative" }, { photoDir: "/tmp/../photos" }, { sourceFingerprint: "a" },
  { sourceFingerprint: "A".repeat(64) }, { ordinal: 0 }, { observationNote: "😀".repeat(301) },
])("원본이 아닌 이름이나 잘못된 참조 %j를 거절한다", (extra) => {
  expect(resolvedAssetSchema.safeParse(asset("101", extra)).success).toBe(false);
  expectError(() => compileContentDraft(draft([image()]), [asset("101", extra)]), "CONTENT_DRAFT_ASSET_INVALID");
});

test("원본 누락·중복 정의·서로 다른 디렉터리를 거절한다", () => {
  expectError(() => compileContentDraft(draft([image()]), []), "CONTENT_DRAFT_ASSET_INVALID");
  expectError(() => compileContentDraft(draft([image()]), [asset(), asset()]), "CONTENT_DRAFT_ASSET_INVALID");
  expectError(() => compileContentDraft(draft([image()]), [asset(), asset("102", { photoDir: `${photoDir}/other` })]),
    "CONTENT_DRAFT_ASSET_INVALID");
});

test("원고·중첩 블록·resolved asset·snapshot의 알 수 없는 필드와 버전을 거절한다", () => {
  const valid = draft([image()]);
  const invalidDrafts = [
    { ...valid, owner: "user" }, { ...valid, schemaVersion: 2 },
    { ...valid, blocks: [{ type: "unknown" }] },
    { ...valid, blocks: [{ type: "image", assetId: "101", fileName: "101.jpg" }] },
    { ...valid, blocks: [{ type: "image", assetId: "101", observationRef: { id: "901", revision: 1, owner: "user" } }] },
  ];
  invalidDrafts.forEach((value) => expect(canonicalDraftV1Schema.safeParse(value).success).toBe(false));
  expect(resolvedAssetSchema.safeParse({ ...asset(), owner: "user" }).success).toBe(false);
  const snapshot = compileContentDraft(valid, [asset()]);
  for (const extra of [{ owner: "user" }, { schemaVersion: 2 }, { compilerVersion: "content-compiler-v2" }])
    expect(compiledSnapshotV1Schema.safeParse({ ...snapshot, ...extra }).success).toBe(false);
  expect(compiledSnapshotV1Schema.safeParse({ ...snapshot, photoNotes: { "1\n": "안내" } }).success).toBe(false);
});

test("태그와 문구·caption·순서·원본 지문·asset ID·관찰 안내의 단독 변경은 hash를 바꾼다", () => {
  const blocks: CanonicalBlock[] = [paragraph("원문"), { type: "image", assetId: "101", caption: "설명" }];
  const originalAsset = asset("101", { observationNote: "안내" });
  const original = compileContentDraft(draft(blocks), [originalAsset]);
  const changed = [
    compileContentDraft(draft(blocks, { tags: ["다른 태그"] }), [originalAsset]),
    compileContentDraft(draft([paragraph("바뀐 문구"), blocks[1]!]), [originalAsset]),
    compileContentDraft(draft([blocks[0]!, { type: "image", assetId: "101", caption: "바뀐 설명" }]), [originalAsset]),
    compileContentDraft(draft([...blocks].reverse()), [originalAsset]),
    compileContentDraft(draft(blocks), [{ ...originalAsset, sourceFingerprint: "b".repeat(64) }]),
    compileContentDraft(draft([blocks[0]!, { type: "image", assetId: "102", caption: "설명" }]),
      [{ ...originalAsset, assetId: "102" }]),
    compileContentDraft(draft(blocks), [{ ...originalAsset, observationNote: "바뀐 안내" }]),
  ];
  changed.forEach((snapshot) => expect(snapshot.snapshotHash).not.toBe(original.snapshotHash));
  for (const snapshot of changed.slice(4)) expect(snapshot.payloadFingerprint).toBe(original.payloadFingerprint);
  const moved = compileContentDraft(draft(blocks), [{ ...originalAsset, photoDir: `${photoDir}/new` }]);
  expect(moved.payloadFingerprint).not.toBe(original.payloadFingerprint);
  const ref = compileContentDraft(draft([blocks[0]!, { type: "image", assetId: "101", caption: "설명",
    observationRef: { id: "901", revision: 1 } }]), [originalAsset]);
  expect(ref.payloadFingerprint).toBe(original.payloadFingerprint);
  expect(ref.snapshotHash).not.toBe(original.snapshotHash);
});

test("canonicalBlocks·manifest·notes·버전만 바꾼 전체 입력도 기존 hash로 수용할 수 없다", () => {
  const snapshot = compileContentDraft(canonicalDraftV1Schema.parse(golden.draft),
    resolvedAssetSchema.array().parse(golden.assets));
  const mutate = (change: (value: CompiledSnapshotV1) => void) => {
    const changed = structuredClone(snapshot);
    change(changed);
    expect(sha(snapshotJson(changed))).not.toBe(snapshot.snapshotHash);
  };
  mutate((value) => { value.canonicalBlocks[0] = ["paragraph", "바뀐 원문"]; });
  mutate((value) => { value.assetManifest[0]!.sourceFingerprint = "c".repeat(64); });
  mutate((value) => { value.assetManifest[0]!.assetId = "999"; });
  mutate((value) => { value.assetManifest[0]!.fileName = "other.jpg"; });
  mutate((value) => { value.photoNotes["1"] = "바뀐 안내"; });
  mutate((value) => { Object.assign(value, { schemaVersion: 2 }); });
  mutate((value) => { Object.assign(value, { compilerVersion: "content-compiler-v2" }); });
});

test("문자열 공백과 Unicode 정규화 형태를 자동으로 바꾸지 않는다", () => {
  const original = compileContentDraft(draft([paragraph(" e\u0301 / 😀 ")], { tags: [" Photo "] }), []);
  const normalized = compileContentDraft(draft([paragraph(" é / 😀 ")], { tags: [" Photo "] }), []);
  expect(original.payload.body).toBe(" e\u0301 / 😀 ");
  expect(original.payload.tags).toEqual([" Photo "]);
  expect(original.payloadFingerprint).not.toBe(normalized.payloadFingerprint);
});
