import { afterEach, expect, test } from "bun:test";
import { mkdtemp, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { type DraftInput, parseBody } from "../src/draft.ts";
import { closeTab, type EditorPage, openWriteTab } from "../src/editor/page.ts";
import { photos } from "../src/editor/photos.ts";
import { EDITOR_METHODS, FakeCdp, FakeEditor } from "./fake-cdp.ts";
import {
  type SnapshotGuard,
  SnapshotGuardError,
} from "../src/content-snapshot-guard.ts";
import { imagePlaceholder } from "../src/editor/text.ts";
import { sha256, syntheticSnapshot } from "./content-snapshot-fixture.ts";

const TIMES = {
  stepSeconds: 0.1,
  photoSeconds: 0.3,
  saveSeconds: 0.3,
  openSeconds: 0.2,
};
// JPEG 서명 뒤에 시험용 바이트를 붙인 가짜 사진.
const PHOTO = new Uint8Array([
  0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
]);
const cleanups: Array<() => Promise<void> | void> = [];

afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

async function setup(body: string) {
  // 사진 디렉터리를 주인의 첨부 디렉터리로도 쓴다. 커넥터가 링크를 거절하므로 실제 경로를 쓴다.
  const photoDir = await realpath(
    await mkdtemp(join(tmpdir(), "naver-blog-photos-")),
  );
  cleanups.push(() => rm(photoDir, { recursive: true, force: true }));
  await writeFile(join(photoDir, "101.jpg"), PHOTO);
  const cdp = new FakeCdp({ allowed: EDITOR_METHODS });
  const editor = new FakeEditor();
  editor.install(cdp);
  cleanups.push(() => cdp.stop());
  const { targetId, page } = await openWriteTab(cdp.url, "example-blog", TIMES);
  cleanups.push(async () => {
    page.close();
    await closeTab(cdp.url, targetId);
  });
  page.stage = "photos";
  const input: DraftInput = {
    title: "가상 제목",
    category: "가상국수로그",
    tags: [],
    body,
    photo_dir: photoDir,
  };
  return {
    cdp,
    editor,
    page: page as EditorPage,
    input,
    blocks: parseBody(body),
    attachmentDir: photoDir,
  };
}

const paramsOf = (cdp: FakeCdp, method: string) =>
  cdp.calls.filter((call) => call.method === method).map((call) => call.params);

test("파일 선택 창의 backendNodeId 로 찾은 input 에 사진 바이트를 넣고 문서 너비를 적용한다", async () => {
  const { cdp, editor, page, input, blocks, attachmentDir } = await setup(
    "앞 줄\n[사진 1: 101.jpg]",
  );
  editor.body = ["앞 줄", "[사진 자리: 101.jpg]"];

  await photos(page, input, blocks, "draft-hash", attachmentDir);

  expect(paramsOf(cdp, "DOM.resolveNode")).toEqual([
    { backendNodeId: editor.backendNodeId },
  ]);
  const [call] = paramsOf(cdp, "Runtime.callFunctionOn");
  expect(call.objectId).toBe(`file-input-${editor.backendNodeId}`);
  expect(call.arguments).toEqual([
    { value: Buffer.from(PHOTO).toString("base64") },
    { value: "101.jpg" },
    { value: "image/jpeg" },
  ]);
  expect(paramsOf(cdp, "Page.setInterceptFileChooserDialog")).toEqual([
    { enabled: true },
    { enabled: false },
  ]);
  expect(editor.images).toEqual([{ fitted: true }]);
  expect(editor.body).toEqual(["앞 줄", ""]);
  expect(cdp.methods()).not.toContain("DOM.setFileInputFiles");
  expect(cdp.violations).toEqual([]);
});

test("선택 창 이벤트가 오지 않으면 photo_upload_failed 와 첫째 사진 자리로 끝나고 가로채기를 끈다", async () => {
  const { cdp, editor, page, input, blocks, attachmentDir } =
    await setup("[사진 1: 101.jpg]");
  editor.body = ["[사진 자리: 101.jpg]"];
  editor.chooser = false;

  const failure = await photos(
    page,
    input,
    blocks,
    "draft-hash",
    attachmentDir,
  ).catch((error) => error);

  expect(failure.code).toBe("photo_upload_failed");
  expect(failure.stage).toBe("photos");
  expect(failure.extra).toEqual({ photo: 1 });
  expect(failure.message).not.toContain(input.photo_dir);
  expect(paramsOf(cdp, "Page.setInterceptFileChooserDialog")).toEqual([
    { enabled: true },
    { enabled: false },
  ]);
  expect(paramsOf(cdp, "DOM.resolveNode")).toEqual([]);
  expect(cdp.methods()).not.toContain("DOM.setFileInputFiles");
});

test("사진 줄이 없으면 파일 선택 창을 가로채지 않는다", async () => {
  const { cdp, page, input, blocks, attachmentDir } =
    await setup("글만 있는 본문");

  await photos(page, input, blocks, "draft-hash", attachmentDir);

  expect(cdp.methods()).not.toContain("Page.setInterceptFileChooserDialog");
  expect(cdp.methods()).not.toContain("Input.dispatchMouseEvent");
});

test("guard 없는 DSL 에서 둘째 사진 업로드 실패는 기존 사진 유무와 무관하게 photo=2 다", async () => {
  for (const initial of [0, 1]) {
    const { cdp, editor, page, input, blocks, attachmentDir } = await setup(
      "[사진 1: 101.jpg]\n[사진 2: 102.jpg]\n[사진 3: 103.jpg]",
    );
    await writeFile(join(attachmentDir, "102.jpg"), PHOTO);
    await writeFile(join(attachmentDir, "103.jpg"), PHOTO);
    editor.body = [
      initial === 0 ? "[사진 자리: 101.jpg]" : "",
      "[사진 자리: 102.jpg]",
      "[사진 자리: 103.jpg]",
    ];
    if (initial === 1) editor.images.push({ fitted: true });
    cdp.on("Runtime.callFunctionOn", () => {
      if (editor.images.length === 1) throw new Error("둘째 사진 업로드 실패");
      editor.images.push({ fitted: false });
      return { result: { type: "number", value: 1 } };
    });
    const failure = await photos(
      page,
      input,
      blocks,
      "draft-hash",
      attachmentDir,
    ).catch((error) => error);
    expect(failure.code).toBe("photo_upload_failed");
    expect(failure.extra).toEqual({ photo: 2 });
    expect(editor.images).toHaveLength(1);
    expect(paramsOf(cdp, "Runtime.callFunctionOn")).toHaveLength(2 - initial);
  }
});

/** guard 가 있는 사진 넣기. 업로드한 바이트의 지문과 authorize 한 자리를 순서대로 모은다. */
async function guarded(
  count: number,
  order?: number[],
  deny?: (position: number) => boolean,
) {
  const base = await setup("자리만 쓰는 본문");
  const snapshot = await syntheticSnapshot(base.input.photo_dir!, count, order);
  const blocks = parseBody(snapshot.payload.body);
  base.editor.body = blocks.map((block) =>
    block.type === "image"
      ? imagePlaceholder(block)
      : block.type === "text"
        ? block.line
        : "",
  );
  const authorized: number[] = [];
  const guard: SnapshotGuard = {
    manifest: snapshot.assetManifest,
    authorizePhoto: async (position) => {
      authorized.push(position);
      if (deny?.(position)) throw new SnapshotGuardError("authorize_denied");
    },
  };
  const uploaded = () =>
    paramsOf(base.cdp, "Runtime.callFunctionOn").map((call) =>
      sha256(Buffer.from(call.arguments[0].value, "base64")),
    );
  return {
    ...base,
    snapshot,
    blocks,
    guard,
    authorized,
    uploaded,
    input: snapshot.payload,
  };
}

test("30장 서로 다른 합성 바이트를 manifest 순서와 자리별 authorize 뒤 같은 바이트로 넣는다", async () => {
  const {
    page,
    input,
    blocks,
    attachmentDir,
    guard,
    snapshot,
    authorized,
    uploaded,
  } = await guarded(30);
  await photos(page, input, blocks, "draft-hash", attachmentDir, guard);
  expect(authorized).toEqual(
    snapshot.assetManifest.map((entry) => entry.position),
  );
  expect(uploaded()).toEqual(
    snapshot.assetManifest.map((entry) => entry.sourceFingerprint),
  );
  expect(new Set(uploaded()).size).toBe(30);
}, 30_000);

test("authorize 가 자리 하나를 거절하면 그 사진부터 업로드하지 않는다", async () => {
  const { page, input, blocks, attachmentDir, guard, uploaded } = await guarded(
    3,
    undefined,
    (position) => position === 2,
  );
  const failure = await photos(
    page,
    input,
    blocks,
    "draft-hash",
    attachmentDir,
    guard,
  ).catch((error) => error);
  expect(failure).toBeInstanceOf(SnapshotGuardError);
  expect(failure.code).toBe("authorize_denied");
  expect(uploaded()).toHaveLength(1);
});

test("manifest 누락·순서 어긋남·파일 교체·검사하지 않은 기존 사진은 업로드 전에 막는다", async () => {
  const cases: Array<
    (ctx: Awaited<ReturnType<typeof guarded>>) => Promise<void> | void
  > = [
    (ctx) => void (ctx.guard.manifest = ctx.guard.manifest.slice(1)),
    (ctx) => void (ctx.guard.manifest = [...ctx.guard.manifest].reverse()),
    (ctx) =>
      writeFile(
        join(ctx.attachmentDir, "2.jpg"),
        new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 9]),
      ),
    (ctx) => void ctx.editor.images.push({ fitted: true }),
  ];
  // 파일 교체만 첫 사진을 올린 뒤 둘째 자리에서 멈추고, 나머지는 업로드 0회다.
  const expectedUploads = [0, 0, 1, 0];
  for (const [index, change] of cases.entries()) {
    const ctx = await guarded(2);
    await change(ctx);
    const failure = await photos(
      ctx.page,
      ctx.input,
      ctx.blocks,
      "draft-hash",
      ctx.attachmentDir,
      ctx.guard,
    ).catch((error) => error);
    expect(failure).toBeInstanceOf(SnapshotGuardError);
    expect(failure.code).toBe("photo_mismatch");
    expect(ctx.uploaded()).toHaveLength(expectedUploads[index]!);
  }
});

test("readPhoto 뒤 파일이 바뀌어도 지문을 검사한 바로 그 바이트를 업로드한다", async () => {
  const ctx = await guarded(1);
  const original = ctx.guard.authorizePhoto;
  ctx.guard.authorizePhoto = async (position) => {
    // 지문 검사 뒤, 업로드 직전에 원본을 바꾼다.
    await writeFile(
      join(ctx.attachmentDir, "1.jpg"),
      new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 9]),
    );
    await original(position);
  };
  await photos(
    ctx.page,
    ctx.input,
    ctx.blocks,
    "draft-hash",
    ctx.attachmentDir,
    ctx.guard,
  );
  expect(ctx.uploaded()).toEqual([
    ctx.snapshot.assetManifest[0]!.sourceFingerprint,
  ]);
});
