import { afterEach, expect, test } from "bun:test";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { type DraftInput, parseBody } from "../src/draft.ts";
import { runDraft, type RunStage } from "../src/editor/run.ts";
import { EDITOR_METHODS, FakeCdp, FakeEditor } from "./fake-cdp.ts";

const TIMES = { stepSeconds: 0.1, photoSeconds: 0.3, saveSeconds: 0.3, openSeconds: 0.2 };
const PHOTO = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4]);
const PLACE = { name: "가상국수", address: "서울특별시 가상구 예시로 1" };
const BODY = [
  "[스티커: ogq_abc123-4]",
  "안녕하세요 가상블로그입니다",
  "오늘은 동네 국숫집에 다녀왔어요 😋",
  "",
  "[사진 1: 101.jpg]",
  "간판부터 정겨운 느낌",
  `[지도: ${PLACE.name} | ${PLACE.address}]`,
].join("\n");
const ALL_STAGES: RunStage[] = [
  "open",
  "fill",
  "photos",
  "components",
  "settings",
  "save",
  "save_clicking",
  "state",
];
const cleanups: Array<() => Promise<void> | void> = [];

afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

/** 가짜 브라우저 하나에 다른 탭 하나를 미리 열어 두고, 편집기 대역과 초안을 준비한다. */
async function setup() {
  const photoDir = await mkdtemp(join(tmpdir(), "naver-blog-run-"));
  cleanups.push(() => rm(photoDir, { recursive: true, force: true }));
  await writeFile(join(photoDir, "101.jpg"), PHOTO);
  const cdp = new FakeCdp({ allowed: EDITOR_METHODS });
  cleanups.push(() => cdp.stop());
  const otherTab = cdp.addTarget("https://example.com/other");
  const editor = new FakeEditor();
  editor.places = { [PLACE.name]: [PLACE] };
  editor.install(cdp);
  const input: DraftInput = {
    title: "가상국수 다녀온 날",
    category: "가상국수로그",
    tags: ["가상국수", "점심"],
    body: BODY,
    photo_dir: photoDir,
  };
  const env = { NAVER_BLOG_CDP_URL: cdp.url, NAVER_BLOG_ID: "example-blog" };
  const stages: string[] = [];
  const run = (signal = new AbortController().signal) =>
    runDraft(env, parseBody(BODY), input, (stage) => void stages.push(stage), signal, TIMES);
  return { cdp, editor, input, env, stages, run, otherTab };
}

/** 연 탭 하나만 한 번 닫혔고 미리 열려 있던 탭은 그대로인지 본다. */
function expectOnlyOwnTabClosed(cdp: FakeCdp, otherTab: string) {
  const closes = cdp.httpRequests.filter((request) => request.startsWith("GET /json/close/"));
  expect(closes).toHaveLength(1);
  expect(closes[0]).not.toBe(`GET /json/close/${otherTab}`);
  expect(cdp.targetIds()).toEqual([otherTab]);
}

test("모든 단계가 기대한 화면이면 단계를 차례로 알리고 저장 수 3 에서 4 를 돌려준다", async () => {
  const { cdp, editor, input, stages, run, otherTab } = await setup();

  const result = await run();

  expect(stages).toEqual(ALL_STAGES);
  expect(result.savedBefore).toBe(3);
  expect(result.savedAfter).toBe(4);
  expect(result.state).toEqual({
    title: input.title,
    photos: 1,
    fitted_photos: 1,
    stickers: 1,
    maps: 1,
    category: "가상국수로그",
    tags: ["가상국수", "점심"],
    saved_count: 4,
  });
  expect(editor.stickers).toEqual(["ogq_abc123-4"]);
  expect(editor.maps).toEqual([PLACE]);
  expect(cdp.opened).toEqual(["https://blog.naver.com/PostWriteForm.naver?blogId=example-blog"]);
  expect(editor.scripts.length).toBeGreaterThan(0);
  expect(editor.scripts.filter((script) => script.includes("tpb*i.publish"))).toEqual([]);
  expect(cdp.methods()).not.toContain("DOM.setFileInputFiles");
  expect(cdp.violations).toEqual([]);
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("로그인 화면으로 넘어가면 login_required 이고 탭을 닫는다", async () => {
  const { cdp, editor, run, otherTab } = await setup();
  editor.host = "nid.naver.com";
  editor.docTitle = "NAVER 로그인";

  const failure = await run().catch((error) => error);

  expect(failure.code).toBe("login_required");
  expect(failure.stage).toBe("open");
  expect(failure.message).not.toContain(cdp.url);
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("카테고리가 없으면 category_not_found 와 있는 카테고리 이름을 돌려준다", async () => {
  const { cdp, editor, run, otherTab } = await setup();
  editor.categories = ["일상", "여행"];

  const failure = await run().catch((error) => error);

  expect(failure.code).toBe("category_not_found");
  expect(failure.stage).toBe("settings");
  expect(failure.extra).toEqual({ categories: ["일상", "여행"] });
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("상호명과 주소가 맞는 장소가 둘이면 place_not_unique 와 후보 둘을 돌려준다", async () => {
  const { cdp, editor, run, otherTab } = await setup();
  editor.places = { [PLACE.name]: [PLACE, PLACE] };

  const failure = await run().catch((error) => error);

  expect(failure.code).toBe("place_not_unique");
  expect(failure.stage).toBe("components");
  expect(failure.extra).toEqual({ place: PLACE, candidates: [PLACE, PLACE] });
  expect(editor.maps).toEqual([]);
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("저장 수가 늘지 않으면 save_clicking 을 알린 뒤 save_unconfirmed 로 끝난다", async () => {
  const { cdp, editor, stages, run, otherTab } = await setup();
  editor.saveIncrements = false;

  const failure = await run().catch((error) => error);

  expect(failure.code).toBe("save_unconfirmed");
  expect(failure.stage).toBe("save");
  expect(stages).toEqual(ALL_STAGES.slice(0, -1));
  expect(editor.saved).toBe(3);
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("이미 중단된 signal 이면 단계를 시작하지 않고 연 탭을 닫는다", async () => {
  const { cdp, stages, run, otherTab } = await setup();
  const controller = new AbortController();
  controller.abort();

  const failure = await run(controller.signal).catch((error) => error);

  expect(failure.code).toBe("editor_failed");
  expect(failure.message).toBe("aborted");
  expect(stages).toEqual([]);
  expect(cdp.methods()).not.toContain("Runtime.evaluate");
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("열린 alert 이벤트가 오면 Page.handleJavaScriptDialog 로 수락하고 계속한다", async () => {
  const { cdp, editor, stages, run, otherTab } = await setup();
  editor.alertOnEnable = true;

  await run();

  const dialogs = cdp.calls.filter((call) => call.method === "Page.handleJavaScriptDialog");
  expect(dialogs.map((call) => call.params)).toEqual([{ accept: true }]);
  expect(stages).toEqual(ALL_STAGES);
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("저장 확인 뒤 state 가 실패해도 저장 수와 함께 성공하고 state 는 null 이다", async () => {
  const { cdp, input, env, otherTab } = await setup();
  const stages: string[] = [];
  const onStage = (stage: RunStage) => {
    stages.push(stage);
    // 저장을 확인한 뒤부터 화면 스크립트가 모두 실패하게 해 상태 읽기를 깨뜨린다.
    if (stage === "state")
      cdp.on("Runtime.evaluate", () => {
        throw new Error("broken");
      });
  };

  const result = await runDraft(
    env,
    parseBody(BODY),
    input,
    onStage,
    new AbortController().signal,
    TIMES,
  );

  expect(result).toEqual({ state: null, savedBefore: 3, savedAfter: 4 });
  expect(stages).toEqual(ALL_STAGES);
  expectOnlyOwnTabClosed(cdp, otherTab);
});
