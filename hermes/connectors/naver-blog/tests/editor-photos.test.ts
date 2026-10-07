import { afterEach, expect, test } from "bun:test";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { type DraftInput, parseBody } from "../src/draft.ts";
import { closeTab, type EditorPage, openWriteTab } from "../src/editor/page.ts";
import { photos } from "../src/editor/photos.ts";
import { EDITOR_METHODS, FakeCdp, FakeEditor } from "./fake-cdp.ts";

const TIMES = { stepSeconds: 0.1, photoSeconds: 0.3, saveSeconds: 0.3, openSeconds: 0.2 };
// JPEG 서명 뒤에 시험용 바이트를 붙인 가짜 사진.
const PHOTO = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4, 5, 6, 7, 8, 9]);
const cleanups: Array<() => Promise<void> | void> = [];

afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

async function setup(body: string) {
  const photoDir = await mkdtemp(join(tmpdir(), "naver-blog-photos-"));
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
  return { cdp, editor, page: page as EditorPage, input, blocks: parseBody(body) };
}

const paramsOf = (cdp: FakeCdp, method: string) =>
  cdp.calls.filter((call) => call.method === method).map((call) => call.params);

test("파일 선택 창의 backendNodeId 로 찾은 input 에 사진 바이트를 넣고 문서 너비를 적용한다", async () => {
  const { cdp, editor, page, input, blocks } = await setup("앞 줄\n[사진 1: 101.jpg]");
  editor.body = ["앞 줄", "[사진 자리: 101.jpg]"];

  await photos(page, input, blocks, "draft-hash");

  expect(paramsOf(cdp, "DOM.resolveNode")).toEqual([{ backendNodeId: editor.backendNodeId }]);
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
  const { cdp, editor, page, input, blocks } = await setup("[사진 1: 101.jpg]");
  editor.body = ["[사진 자리: 101.jpg]"];
  editor.chooser = false;

  const failure = await photos(page, input, blocks, "draft-hash").catch((error) => error);

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
  const { cdp, page, input, blocks } = await setup("글만 있는 본문");

  await photos(page, input, blocks, "draft-hash");

  expect(cdp.methods()).not.toContain("Page.setInterceptFileChooserDialog");
  expect(cdp.methods()).not.toContain("Input.dispatchMouseEvent");
});
