import { afterEach, expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { listCategories } from "../src/categories.ts";
import { listDrafts, readDraft } from "../src/drafts.ts";
import { ToolError } from "../src/errors.ts";
import { EDITOR_METHODS, FakeCdp, FakeEditor } from "./fake-cdp.ts";

const TIMES = {
  stepSeconds: 0.1,
  photoSeconds: 0.3,
  saveSeconds: 0.3,
  openSeconds: 0.2,
};
const DRAFT_NOTICE = "작성 중인 글이 있습니다. 이어서 작성하시겠습니까?";
const cleanups: Array<() => Promise<void> | void> = [];

afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

async function setup() {
  const cdp = new FakeCdp({
    allowed: [...EDITOR_METHODS, "Storage.getCookies"],
  });
  cleanups.push(() => cdp.stop());
  cdp.on("Storage.getCookies", () => ({
    cookies: [
      { name: "NID_AUT", domain: ".naver.com" },
      { name: "NID_SES", domain: ".naver.com" },
    ],
  }));
  const editor = new FakeEditor();
  editor.install(cdp);
  editor.drafts = [
    {
      logNo: 224000000001,
      title: "가상 글",
      modiDate: 0,
      components: [],
      category: "일상",
      tags: [],
    },
  ];
  const base = await mkdtemp(join(tmpdir(), "naver-blog-read-popup-"));
  cleanups.push(() => rm(base, { recursive: true, force: true }));
  const env = {
    NAVER_BLOG_BROWSER_URL: cdp.url,
    NAVER_BLOG_ID: "example-blog",
    NAVER_BLOG_JOB_DIR: join(base, "jobs"),
  };
  return { editor, env };
}

/** 일을 돌리는 동안 표준 오류에 쓴 줄을 모은다. */
async function captureStderr<T>(work: () => Promise<T>) {
  const lines: string[] = [];
  const original = process.stderr.write;
  process.stderr.write = ((chunk: string | Uint8Array) => {
    lines.push(String(chunk));
    return true;
  }) as typeof process.stderr.write;
  try {
    return { result: await work(), lines };
  } finally {
    process.stderr.write = original;
  }
}

test("글쓰기 화면을 연 뒤 늦게 뜬 도움말 알림은 닫기 단추로 닫고 카테고리를 읽는다", async () => {
  const { editor, env } = await setup();
  editor.latePopup = "가상 도움말 안내";
  editor.popupHelp = true;
  editor.popupButtons = ["닫기"];
  editor.popupClosedBy = ["닫기"];

  const result = await listCategories(env, TIMES);

  expect(result.categories).toEqual(["가상국수로그", "일상"]);
  expect(editor.popupClicks).toEqual(["닫기"]);
  expect(editor.popup).toBe("");
});

test("도움말 알림에 닫기가 없으면 확인으로 닫는다", async () => {
  const { editor, env } = await setup();
  editor.latePopup = "가상 도움말 안내";
  editor.popupHelp = true;
  editor.popupButtons = ["확인"];
  editor.popupClosedBy = ["확인"];

  const result = await listCategories(env, TIMES);

  expect(result.count).toBe(2);
  expect(editor.popupClicks).toEqual(["확인"]);
});

test("늦게 뜬 작성 중인 글 알림은 어떤 단추도 누르지 않고 NAVER_BLOG_EDITOR_IN_USE 다", async () => {
  const { editor, env } = await setup();
  editor.latePopup = DRAFT_NOTICE;

  const error = await listCategories(env, TIMES).catch((caught) => caught);

  expect(error).toBeInstanceOf(ToolError);
  expect((error as ToolError).code).toBe("NAVER_BLOG_EDITOR_IN_USE");
  expect(editor.popupClicks).toEqual([]);
  expect(editor.popup).toBe(DRAFT_NOTICE);
});

test("늦게 뜬 모르는 알림은 누르지 않고 EDITOR_CHANGED 이며 진단 줄에 kind unknown 이 있다", async () => {
  const { editor, env } = await setup();
  editor.latePopup = "가상 안내: 이 글은 진단 줄에 싣지 않는다";
  editor.popupButtons = ["닫기", "가상 단추"];
  editor.popupClosedBy = ["닫기"];

  const { result, lines } = await captureStderr(() =>
    listCategories(env, TIMES).catch((caught) => caught),
  );

  expect((result as ToolError).code).toBe("NAVER_BLOG_EDITOR_CHANGED");
  expect(editor.popupClicks).toEqual([]);
  const text = lines.join("");
  const entry = JSON.parse(
    lines.find((line) => line.includes("naver_blog_diagnostic"))!,
  ).naver_blog_diagnostic;
  expect(entry.tool).toBe("list_categories");
  expect(entry.popup_diagnostics).toEqual({
    kind: "unknown",
    box: "div.se-popup-container.__se-pop-layer",
    buttons: ["닫기", "other"],
    dismissed: false,
  });
  expect(text).not.toContain("가상 안내");
  expect(text).not.toContain("가상 단추");
});

test("도움말이어도 위험한 단추가 있으면 누르지 않고 멈춘다", async () => {
  const { editor, env } = await setup();
  editor.latePopup = "가상 도움말 안내";
  editor.popupHelp = true;
  editor.popupButtons = ["닫기", "삭제"];
  editor.popupClosedBy = ["닫기"];

  const error = await listCategories(env, TIMES).catch((caught) => caught);

  expect((error as ToolError).code).toBe("NAVER_BLOG_EDITOR_CHANGED");
  expect(editor.popupClicks).toEqual([]);
});

test("임시저장 목록도 늦게 뜬 도움말 알림은 닫고, 작성 중인 글 알림은 누르지 않고 읽는다", async () => {
  const help = await setup();
  help.editor.latePopup = "가상 도움말 안내";
  help.editor.popupHelp = true;
  help.editor.popupButtons = ["닫기"];
  help.editor.popupClosedBy = ["닫기"];

  expect((await listDrafts(help.env, TIMES)).count).toBe(1);
  expect(help.editor.popupClicks).toEqual(["닫기"]);

  const notice = await setup();
  notice.editor.latePopup = DRAFT_NOTICE;

  expect((await listDrafts(notice.env, TIMES)).count).toBe(1);
  expect(notice.editor.popupClicks).toEqual([]);
  expect(notice.editor.popup).toBe(DRAFT_NOTICE);
});

test("임시저장 목록은 늦게 뜬 모르는 알림이 남아도 누르지 않고 목록을 읽는다", async () => {
  const { editor, env } = await setup();
  editor.latePopup = "가상 안내";
  editor.popupButtons = ["닫기", "가상 단추"];

  expect((await listDrafts(env, TIMES)).count).toBe(1);
  expect(editor.popupClicks).toEqual([]);
});

test("글 읽기는 늦게 뜬 작성 중인 글 알림의 단추를 누르지 않고 NAVER_BLOG_EDITOR_IN_USE 다", async () => {
  const { editor, env } = await setup();
  editor.latePopup = DRAFT_NOTICE;

  const error = await readDraft(env, "224000000001", TIMES).catch(
    (caught) => caught,
  );

  expect((error as ToolError).code).toBe("NAVER_BLOG_EDITOR_IN_USE");
  expect(editor.popupClicks).toEqual([]);
});

test("도움말이 세 번 이어서 떠도 모두 닫았으면 읽기를 이어 간다", async () => {
  const { editor, env } = await setup();
  editor.latePopup = "가상 도움말 안내";
  editor.popupHelp = true;
  editor.popupButtons = ["닫기"];
  editor.popupClosedBy = ["닫기"];
  editor.chainedPopups = 2;

  const result = await listCategories(env, TIMES);

  expect(result.count).toBe(2);
  expect(editor.popupClicks).toEqual(["닫기", "닫기", "닫기"]);
});
