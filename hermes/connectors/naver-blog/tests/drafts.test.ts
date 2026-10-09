import { afterEach, expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { draftRevision } from "../src/changes.ts";
import { listDrafts, readDraft } from "../src/drafts.ts";
import { ToolError } from "../src/errors.ts";
import { acquireLock, createState, openJobDir } from "../src/jobs.ts";
import { EDITOR_METHODS, FakeCdp, type FakeDraft, FakeEditor, upstreamText } from "./fake-cdp.ts";

const TIMES = { stepSeconds: 0.1, photoSeconds: 0.3, saveSeconds: 0.3, openSeconds: 0.2 };
const cleanups: Array<() => Promise<void> | void> = [];

afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

const paragraph = (value: string) => ({
  id: "SE-p",
  nodes: [{ id: "SE-n", value, "@ctype": "textNode" }],
  "@ctype": "paragraph",
});

const DRAFTS: FakeDraft[] = [
  {
    logNo: 224000000001,
    title: "가상국수 다녀온 날",
    modiDate: Date.UTC(2026, 9, 1, 3, 0, 0),
    components: [
      { "@ctype": "documentTitle", title: [paragraph("가상국수 다녀온 날")] },
      { "@ctype": "text", value: [paragraph("안녕하세요"), paragraph("")] },
      { "@ctype": "image", src: "https://example.com/a.jpg" },
      { "@ctype": "text", value: [paragraph("간판부터 정겨운 느낌")] },
      { "@ctype": "sticker" },
      { "@ctype": "placesMap" },
      { "@ctype": "quotation" },
    ],
    category: "가상국수로그",
    tags: ["가상국수", "점심"],
  },
  {
    logNo: 224000000002,
    title: "두 번째 글",
    modiDate: Date.UTC(2026, 8, 1),
    components: [{ "@ctype": "documentTitle", title: [paragraph("두 번째 글")] }],
    category: "일상",
    tags: [],
  },
];

async function setup() {
  const cdp = new FakeCdp({ allowed: EDITOR_METHODS });
  cleanups.push(() => cdp.stop());
  const otherTab = cdp.addTarget("https://example.com/other");
  const editor = new FakeEditor();
  editor.drafts = DRAFTS;
  editor.install(cdp);
  const jobDir = join(await mkdtemp(join(tmpdir(), "naver-blog-drafts-")), "jobs");
  cleanups.push(() => rm(join(jobDir, ".."), { recursive: true, force: true }));
  const env = {
    NAVER_BLOG_BROWSER_URL: cdp.url,
    NAVER_BLOG_ID: "example-blog",
    NAVER_BLOG_JOB_DIR: jobDir,
  };
  return { cdp, editor, env, otherTab };
}

/** 연 탭 하나만 닫혔고 미리 열려 있던 탭은 그대로인지 본다. */
function expectOnlyOwnTabClosed(cdp: FakeCdp, otherTab: string) {
  expect(cdp.opened).toEqual(["https://blog.naver.com/PostWriteForm.naver?blogId=example-blog"]);
  expect(cdp.targetIds()).toEqual([otherTab]);
  expect(cdp.violations).toEqual([]);
}

test("목록은 목록 API 의 글 번호, 제목, 고친 시각을 차례대로 돌려주고 글을 불러오지 않는다", async () => {
  const { cdp, editor, env, otherTab } = await setup();

  const result = await listDrafts(env, TIMES);

  expect(result).toEqual({
    count: 2,
    drafts: [
      { draft_id: "224000000001", title: "가상국수 다녀온 날", saved_at: "2026-10-01T03:00:00.000Z" },
      { draft_id: "224000000002", title: "두 번째 글", saved_at: "2026-09-01T00:00:00.000Z" },
    ],
  });
  expect(editor.loaded).toBeNull();
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("목록 API 가 실패하면 원문 없이 NAVER_BLOG_UNAVAILABLE 이다", async () => {
  const { cdp, editor, env, otherTab } = await setup();
  editor.listStatus = 500;

  const error = await listDrafts(env, TIMES).catch((caught) => caught);

  expect(error).toBeInstanceOf(ToolError);
  expect((error as ToolError).code).toBe("NAVER_BLOG_UNAVAILABLE");
  expect(String(error)).not.toContain(upstreamText);
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("글 하나를 불러와 제목, 카테고리, 태그, 기존 구성요소 줄이 든 본문과 지문을 돌려준다", async () => {
  const { cdp, editor, env, otherTab } = await setup();

  const result = await readDraft(env, "224000000001", TIMES);

  const draft = {
    title: "가상국수 다녀온 날",
    category: "가상국수로그",
    tags: ["가상국수", "점심"],
    body: [
      "안녕하세요",
      "",
      "[기존 사진 1]",
      "간판부터 정겨운 느낌",
      "[기존 스티커 1]",
      "[기존 지도 1]",
      "[기존 구성요소 1: quotation]",
    ].join("\n"),
  };
  expect(result).toEqual({ draft_id: "224000000001", revision: draftRevision(draft), ...draft });
  expect(editor.loaded?.logNo).toBe(224000000001);
  // 저장 단추는 누르지 않는다.
  expect(editor.saved).toBe(3);
  expect(editor.scripts.some((script) => script.includes('=== "저장"'))).toBe(false);
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("목록에 없는 글 번호는 탭을 닫고 NAVER_BLOG_DRAFT_NOT_FOUND 다", async () => {
  const { cdp, editor, env, otherTab } = await setup();

  const error = await readDraft(env, "224000000009", TIMES).catch((caught) => caught);

  expect((error as ToolError).code).toBe("NAVER_BLOG_DRAFT_NOT_FOUND");
  expect(editor.loaded).toBeNull();
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("목록 화면의 차례가 목록 응답과 다르면 글을 누르지 않는다", async () => {
  const { cdp, editor, env, otherTab } = await setup();
  editor.listTitles = ["두 번째 글", "가상국수 다녀온 날"];

  const error = await readDraft(env, "224000000001", TIMES).catch((caught) => caught);

  expect((error as ToolError).code).toBe("NAVER_BLOG_UNAVAILABLE");
  expect(editor.loaded).toBeNull();
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("글쓰기 화면이 로그인 화면으로 넘어가면 NAVER_BLOG_LOGIN_REQUIRED 다", async () => {
  const { cdp, editor, env, otherTab } = await setup();
  editor.host = "nid.naver.com";

  const error = await listDrafts(env, TIMES).catch((caught) => caught);

  expect((error as ToolError).code).toBe("NAVER_BLOG_LOGIN_REQUIRED");
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("같은 블로그에 돌고 있는 저장 작업이 있으면 탭을 열지 않고 NAVER_BLOG_BUSY 다", async () => {
  const { cdp, env } = await setup();
  const dir = await openJobDir(env);
  const jobId = "11111111-2222-4333-8444-555555555555";
  await acquireLock(dir, "example-blog", jobId);
  const now = new Date().toISOString();
  await createState(dir, {
    job_id: jobId,
    status: "running",
    stage: "fill",
    save_clicked: false,
    started_at: now,
    finished_at: null,
    result: null,
    error: null,
    pid: 1,
    heartbeat_at: now,
  });

  const error = await readDraft(env, "224000000001", TIMES).catch((caught) => caught);

  expect((error as ToolError).code).toBe("NAVER_BLOG_BUSY");
  expect(cdp.opened).toEqual([]);
});
