import { afterEach, expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { draftRevision } from "../src/changes.ts";
import { autosaveCheck, listDrafts, readDraft } from "../src/drafts.ts";
import { ToolError } from "../src/errors.ts";
import { acquireLock, createState, openJobDir } from "../src/jobs.ts";
import {
  EDITOR_METHODS,
  FakeCdp,
  type FakeDraft,
  FakeEditor,
  upstreamText,
} from "./fake-cdp.ts";

const TIMES = {
  stepSeconds: 0.1,
  photoSeconds: 0.3,
  saveSeconds: 0.3,
  openSeconds: 0.2,
};
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
      {
        "@ctype": "image",
        id: "SE-synthetic-a",
        fileName: "alpha.jpg",
        src: "https://example.com/a.jpg",
      },
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
    components: [
      { "@ctype": "documentTitle", title: [paragraph("두 번째 글")] },
    ],
    category: "일상",
    tags: [],
  },
];

const LOGIN_COOKIES = [
  { name: "NID_AUT", domain: ".naver.com" },
  { name: "NID_SES", domain: ".naver.com" },
];

async function setup() {
  // 까닭을 모르는 실패 뒤에는 로그인 쿠키를 한 번 더 본다.
  const cdp = new FakeCdp({
    allowed: [...EDITOR_METHODS, "Storage.getCookies"],
  });
  cleanups.push(() => cdp.stop());
  const session = { cookies: LOGIN_COOKIES };
  cdp.on("Storage.getCookies", () => ({ cookies: session.cookies }));
  const otherTab = cdp.addTarget("https://example.com/other");
  const editor = new FakeEditor();
  editor.drafts = structuredClone(DRAFTS);
  editor.install(cdp);
  const jobDir = join(
    await mkdtemp(join(tmpdir(), "naver-blog-drafts-")),
    "jobs",
  );
  cleanups.push(() => rm(join(jobDir, ".."), { recursive: true, force: true }));
  const env = {
    NAVER_BLOG_BROWSER_URL: cdp.url,
    NAVER_BLOG_ID: "example-blog",
    NAVER_BLOG_JOB_DIR: jobDir,
  };
  return { cdp, editor, env, otherTab, session };
}

/** 연 탭 하나만 닫혔고 미리 열려 있던 탭은 그대로인지 본다. */
function expectOnlyOwnTabClosed(cdp: FakeCdp, otherTab: string) {
  expect(cdp.opened).toEqual([
    "https://blog.naver.com/PostWriteForm.naver?blogId=example-blog",
  ]);
  expect(cdp.targetIds()).toEqual([otherTab]);
  expect(cdp.violations).toEqual([]);
}

test("목록은 목록 API 의 글 번호, 제목, 고친 시각을 차례대로 돌려주고 글을 불러오지 않는다", async () => {
  const { cdp, editor, env, otherTab } = await setup();

  const result = await listDrafts(env, TIMES);

  expect(result).toEqual({
    count: 2,
    autosave_requests: 0,
    drafts: [
      {
        draft_id: "224000000001",
        title: "가상국수 다녀온 날",
        saved_at: "2026-10-01T03:00:00.000Z",
      },
      {
        draft_id: "224000000002",
        title: "두 번째 글",
        saved_at: "2026-09-01T00:00:00.000Z",
      },
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
  expect(result).toEqual({
    draft_id: "224000000001",
    revision: draftRevision(draft),
    ...draft,
    photos: [
      {
        position: 1,
        componentIndex: 2,
        componentId: "SE-synthetic-a",
        fileName: "alpha.jpg",
      },
    ],
    photo_count: 1,
    photos_truncated: false,
    autosave_requests: 0,
    autosave_check: {
      count_before: 2,
      count_after: 2,
      saved_at_before: "2026-10-01T03:00:00.000Z",
      saved_at_after: "2026-10-01T03:00:00.000Z",
      changed: false,
    },
  });
  expect(editor.loaded?.logNo).toBe(224000000001);
  // 저장 단추는 누르지 않는다.
  expect(editor.saved).toBe(3);
  expect(editor.scripts.some((script) => script.includes('=== "저장"'))).toBe(
    false,
  );
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("목록에 없는 글 번호는 탭을 닫고 NAVER_BLOG_DRAFT_NOT_FOUND 다", async () => {
  const { cdp, editor, env, otherTab } = await setup();

  const error = await readDraft(env, "224000000009", TIMES).catch(
    (caught) => caught,
  );

  expect((error as ToolError).code).toBe("NAVER_BLOG_DRAFT_NOT_FOUND");
  expect(editor.loaded).toBeNull();
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("목록 화면의 차례가 목록 응답과 다르면 글을 누르지 않는다", async () => {
  const { cdp, editor, env, otherTab } = await setup();
  editor.listTitles = ["두 번째 글", "가상국수 다녀온 날"];

  const error = await readDraft(env, "224000000001", TIMES).catch(
    (caught) => caught,
  );

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

  const error = await readDraft(env, "224000000001", TIMES).catch(
    (caught) => caught,
  );

  expect((error as ToolError).code).toBe("NAVER_BLOG_BUSY");
  expect(cdp.opened).toEqual([]);
});

const DRAFT_NOTICE = "작성 중인 글이 있습니다. 이어서 작성하시겠습니까?";

test("「작성 중인 글」 알림이 떠 있으면 목록은 알림을 닫지 않고 읽는다", async () => {
  const { cdp, editor, env, otherTab } = await setup();
  editor.popup = DRAFT_NOTICE;

  const result = await listDrafts(env, TIMES);

  expect(result.count).toBe(2);
  expect(editor.popup).toBe(DRAFT_NOTICE);
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("「작성 중인 글」 알림이 떠 있으면 글을 불러오지 않고 NAVER_BLOG_EDITOR_IN_USE 다", async () => {
  const { cdp, editor, env, otherTab } = await setup();
  editor.popup = DRAFT_NOTICE;

  const error = await readDraft(env, "224000000001", TIMES).catch(
    (caught) => caught,
  );

  expect((error as ToolError).code).toBe("NAVER_BLOG_EDITOR_IN_USE");
  expect(editor.popup).toBe(DRAFT_NOTICE);
  expect(editor.loaded).toBeNull();
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("제목이 맞아도 다른 글이 불러와지면 문서 번호 대조로 실패한다", async () => {
  const { cdp, editor, env, otherTab } = await setup();
  editor.loadInstead = DRAFTS[1];

  const error = await readDraft(env, "224000000001", TIMES).catch(
    (caught) => caught,
  );

  expect((error as ToolError).code).toBe("NAVER_BLOG_UNAVAILABLE");
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("목록 화면에 그 차례의 단추가 없으면 누르지 않고 실패한다", async () => {
  const { cdp, editor, env, otherTab } = await setup();
  editor.listTitles = ["가상국수 다녀온 날"];

  const error = await readDraft(env, "224000000002", TIMES).catch(
    (caught) => caught,
  );

  expect((error as ToolError).code).toBe("NAVER_BLOG_UNAVAILABLE");
  expect(editor.loaded).toBeNull();
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("제목이 빈 글은 화면 제목과 견주지 않고 문서 번호로 확인해 읽는다", async () => {
  const { editor, env } = await setup();
  const untitled = {
    ...DRAFTS[1]!,
    logNo: 224000000003,
    title: "",
    components: [{ "@ctype": "documentTitle", title: [paragraph("")] }],
  };
  editor.drafts = [...DRAFTS, untitled];
  editor.listTitles = ["가상국수 다녀온 날", "두 번째 글", "제목 없음"];

  const result = await readDraft(env, "224000000003", TIMES);

  expect(result).toMatchObject({
    draft_id: "224000000003",
    title: "",
    body: "",
  });
});

test("편집기 문서의 모양이 다르면 원문 없이 NAVER_BLOG_UNAVAILABLE 이다", async () => {
  const { cdp, editor, env, otherTab } = await setup();
  editor.drafts = [
    {
      ...DRAFTS[0]!,
      components: [{ "@ctype": "text", value: [paragraph(upstreamText)] }],
    },
  ];

  const error = await readDraft(env, "224000000001", TIMES).catch(
    (caught) => caught,
  );

  expect((error as ToolError).code).toBe("NAVER_BLOG_UNAVAILABLE");
  expect(String(error)).not.toContain(upstreamText);
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("시간 상한이 지나면 멈추고 연 탭을 닫는다", async () => {
  const { cdp, editor, env, otherTab } = await setup();
  editor.hangList = true;

  const started = performance.now();
  const error = await listDrafts(env, { ...TIMES, limitMs: 300 }).catch(
    (caught) => caught,
  );

  expect((error as ToolError).code).toBe("NAVER_BLOG_UNAVAILABLE");
  expect(performance.now() - started).toBeLessThan(5_000);
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("새 탭의 편집기에 글이 남아 있으면 알림이 없어도 불러오지 않고 NAVER_BLOG_EDITOR_IN_USE 다", async () => {
  const { cdp, editor, env, otherTab } = await setup();
  editor.document = {
    document: {
      components: [
        { "@ctype": "documentTitle", title: [paragraph("")] },
        { "@ctype": "text", value: [paragraph("쓰던 문장")] },
      ],
    },
  };

  const error = await readDraft(env, "224000000001", TIMES).catch(
    (caught) => caught,
  );

  expect((error as ToolError).code).toBe("NAVER_BLOG_EDITOR_IN_USE");
  expect(editor.loaded).toBeNull();
  expect(editor.listOpen).toBe(false);
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("새 탭의 편집기에 글 번호가 실려 있으면 불러오지 않고 NAVER_BLOG_EDITOR_IN_USE 다", async () => {
  const { cdp, editor, env, otherTab } = await setup();
  editor.document = { document: { components: [] } };
  editor.documentId = "224000000002";

  const error = await readDraft(env, "224000000001", TIMES).catch(
    (caught) => caught,
  );

  expect((error as ToolError).code).toBe("NAVER_BLOG_EDITOR_IN_USE");
  expect(editor.loaded).toBeNull();
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("빈 문단만 있는 새 편집기는 비었다고 보고 글을 불러온다", async () => {
  const { editor, env } = await setup();
  editor.document = {
    document: {
      components: [
        { "@ctype": "documentTitle", title: [paragraph("")] },
        { "@ctype": "text", value: [paragraph(" ")] },
      ],
    },
  };

  const result = await readDraft(env, "224000000002", TIMES);

  expect(result.draft_id).toBe("224000000002");
  expect(editor.loaded?.logNo).toBe(224000000002);
});

test("빈 편집기의 숫자 문서 번호가 목록에 없으면 글을 읽는다", async () => {
  const { editor, env } = await setup();
  editor.document = { document: { components: [] } };
  editor.documentId = "224000000099";
  expect((await readDraft(env, "224000000001", TIMES)).draft_id).toBe(
    "224000000001",
  );
});

test("편집기 객체를 읽지 못하면 글 단추를 누르기 전에 NAVER_BLOG_UNAVAILABLE 이다", async () => {
  const { cdp, editor, env, otherTab } = await setup();
  editor.documentUnavailable = true;

  const error = await readDraft(env, "224000000001", TIMES).catch(
    (caught) => caught,
  );

  expect((error as ToolError).code).toBe("NAVER_BLOG_UNAVAILABLE");
  expect(editor.loaded).toBeNull();
  expect(editor.listOpen).toBe(false);
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("목록 응답이 로그인 화면으로 넘어가고 쿠키가 없으면 NAVER_BLOG_LOGIN_REQUIRED 다", async () => {
  const { cdp, editor, env, otherTab, session } = await setup();
  editor.listStatus = 302;
  session.cookies = [];

  const error = await readDraft(env, "224000000001", TIMES).catch(
    (caught) => caught,
  );

  expect((error as ToolError).code).toBe("NAVER_BLOG_LOGIN_REQUIRED");
  expect(editor.loaded).toBeNull();
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("목록 응답이 실패해도 로그인 쿠키가 있으면 NAVER_BLOG_UNAVAILABLE 로 남긴다", async () => {
  const { env, editor } = await setup();
  editor.listStatus = 500;

  const error = await readDraft(env, "224000000001", TIMES).catch(
    (caught) => caught,
  );

  expect((error as ToolError).code).toBe("NAVER_BLOG_UNAVAILABLE");
});

test("쿠키 실패 진단을 시작하기 전에 읽기 탭을 닫는다", async () => {
  const { cdp, editor, env, otherTab } = await setup();
  editor.listStatus = 500;
  let targetsAtCheck: string[] = [];
  cdp.on("Storage.getCookies", () => {
    targetsAtCheck = cdp.targetIds();
    return { cookies: LOGIN_COOKIES };
  });
  await readDraft(env, "224000000001", TIMES).catch(() => {});
  expect(targetsAtCheck).toEqual([otherTab]);
});

test("불러오는 동안 탭이 로그인 화면으로 넘어가면 NAVER_BLOG_LOGIN_REQUIRED 다", async () => {
  const { cdp, editor, env, otherTab } = await setup();
  editor.loginOnLoad = true;

  const error = await readDraft(env, "224000000001", TIMES).catch(
    (caught) => caught,
  );

  expect((error as ToolError).code).toBe("NAVER_BLOG_LOGIN_REQUIRED");
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test("같은 글을 연달아 읽으면 같은 지문을 돌려주고 목록은 그대로다", async () => {
  const { cdp, editor, env } = await setup();

  const first = await readDraft(env, "224000000001", TIMES);
  // 대역은 탭마다 편집기를 새로 만들지 않아 새 탭의 빈 편집기로 되돌린다.
  editor.document = null;
  editor.documentId = "";
  editor.loaded = null;
  const second = await readDraft(env, "224000000001", TIMES);

  expect(second.revision).toBe(first.revision);
  expect(second.autosave_check?.changed).toBe(false);
  expect(editor.drafts.length).toBe(2);
  expect(cdp.violations).toEqual([]);
});

test("읽기 탭의 자동저장 요청만 세고 중복 이벤트와 다른 주소는 제외한다", async () => {
  const { cdp, env, otherTab } = await setup();
  cdp.on("Network.enable", (_, target) => {
    const request = { url: "https://blog.naver.com/RabbitAutoSaveWrite.naver" };
    cdp.emit(target, "Network.requestWillBeSent", { requestId: "1", request });
    cdp.emit(target, "Network.requestWillBeSent", { requestId: "1", request });
    cdp.emit(target, "Network.requestWillBeSent", { requestId: "2", request });
    cdp.emit(`page/${otherTab}`, "Network.requestWillBeSent", {
      requestId: "3",
      request,
    });
    cdp.emit(target, "Network.requestWillBeSent", {
      requestId: "4",
      request: { url: "https://example.com/RabbitAutoSaveWrite.naver" },
    });
    cdp.emit(target, "Network.requestWillBeSent", {
      requestId: "5",
      request: { url: "https://blog.naver.com/TempPostList.naver" },
    });
    return {};
  });
  const result = await readDraft(env, "224000000001", TIMES);
  expect(result.autosave_requests).toBe(2);
  expect(result.autosave_check?.changed).toBe(false);
  expect(cdp.violations).toEqual([]);
});

test("읽는 동안 자동저장이 그 글의 고친 시각을 바꾸면 autosave_check 가 알린다", async () => {
  const { editor, env } = await setup();
  editor.autosaveOnLoad = true;

  const result = await readDraft(env, "224000000001", TIMES);

  expect(result.autosave_check).toEqual({
    count_before: 2,
    count_after: 2,
    saved_at_before: "2026-10-01T03:00:00.000Z",
    saved_at_after: "2026-10-01T03:00:01.000Z",
    changed: true,
  });
});

test("읽기 앞뒤 목록 비교는 수나 고친 시각이 바뀌거나 글이 사라지면 바뀐 것으로 본다", () => {
  const item = (saved_at: string | null) => ({
    draft_id: "1",
    title: "가",
    saved_at,
  });
  const before = { drafts: [item("2026-10-01T00:00:00.000Z")], count: 1 };

  expect(autosaveCheck(before, null, "1")).toBeNull();
  expect(autosaveCheck(before, before, "1")?.changed).toBe(false);
  expect(
    autosaveCheck(
      before,
      {
        drafts: [
          item("2026-10-01T00:00:00.000Z"),
          { ...item(null), draft_id: "2" },
        ],
        count: 2,
      },
      "1",
    )?.changed,
  ).toBe(true);
  expect(autosaveCheck(before, { drafts: [], count: 0 }, "1")).toMatchObject({
    saved_at_after: null,
    changed: true,
  });
});

test("새 탭의 편집기에 사진만 남아 있어도 불러오지 않고 NAVER_BLOG_EDITOR_IN_USE 다", async () => {
  const { cdp, editor, env, otherTab } = await setup();
  editor.document = {
    document: {
      components: [
        { "@ctype": "documentTitle", title: [paragraph("")] },
        { "@ctype": "image", id: "SE-left" },
      ],
    },
  };

  const error = await readDraft(env, "224000000001", TIMES).catch(
    (caught) => caught,
  );

  expect((error as ToolError).code).toBe("NAVER_BLOG_EDITOR_IN_USE");
  expect(editor.loaded).toBeNull();
  expectOnlyOwnTabClosed(cdp, otherTab);
});

test.each([
  {},
  { document: { components: [{ "@ctype": "unknown-placeholder" }] } },
  { document: {} },
  { document: { components: [{ "@ctype": "text", value: "쓰던 글" }] } },
  { document: { components: [{ "@ctype": "text", value: [{}] }] } },
  {
    document: {
      components: [{ "@ctype": "text", value: [{ nodes: [{ value: 1 }] }] }],
    },
  },
])("편집기 문서 모양이 달라지면 불러오기 전에 멈춘다: %j", async (document) => {
  const { editor, env } = await setup();
  editor.document = document as typeof editor.document;
  const error = await readDraft(env, "224000000001", TIMES).catch(
    (caught) => caught,
  );
  expect((error as ToolError).code).toBe("NAVER_BLOG_UNAVAILABLE");
  expect(editor.loaded).toBeNull();
  expect(editor.listOpen).toBe(false);
});
