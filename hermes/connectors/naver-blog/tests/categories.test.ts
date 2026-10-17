import { afterEach, expect, test } from "bun:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { mkdtemp, readdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  CATEGORY_CACHE_MS,
  categoryProblem,
  listCategories,
} from "../src/categories.ts";
import { canonicalDraftV1Schema } from "../src/content-draft.ts";
import { createSnapshotServer } from "../src/content-snapshot.ts";
import { ToolError } from "../src/errors.ts";
import { createServer } from "../src/server.ts";
import { EDITOR_METHODS, FakeCdp, FakeEditor } from "./fake-cdp.ts";
import golden from "./fixtures/content-snapshot-v1.json";

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

async function setup() {
  const cdp = new FakeCdp({
    allowed: [...EDITOR_METHODS, "Storage.getCookies"],
  });
  cleanups.push(() => cdp.stop());
  cdp.on("Storage.getCookies", () => ({ cookies: [] }));
  const otherTab = cdp.addTarget("https://example.com/other");
  const editor = new FakeEditor();
  editor.install(cdp);
  const base = await mkdtemp(join(tmpdir(), "naver-blog-categories-"));
  cleanups.push(() => rm(base, { recursive: true, force: true }));
  const jobDir = join(base, "jobs");
  const env = {
    NAVER_BLOG_BROWSER_URL: cdp.url,
    NAVER_BLOG_ID: "example-blog",
    NAVER_BLOG_JOB_DIR: jobDir,
  };
  return { cdp, editor, env, otherTab, jobDir };
}

test("카테고리 이름을 차례대로 읽고 항목을 누르지 않으며 읽기 탭만 닫는다", async () => {
  const { cdp, editor, env, otherTab } = await setup();

  const result = await listCategories(env, TIMES);

  expect(result).toEqual({ count: 2, categories: ["가상국수로그", "일상"] });
  expect(editor.category).toBe("");
  expect(editor.loaded).toBeNull();
  expect(cdp.targetIds()).toEqual([otherTab]);
  expect(cdp.violations).toEqual([]);
});

test("읽은 목록에 없는 이름은 고를 수 있는 이름과 함께 알리고 있는 이름은 막지 않는다", async () => {
  const { env } = await setup();
  await listCategories(env, TIMES);

  expect(await categoryProblem(env, "일상")).toBeNull();
  expect(await categoryProblem(env, " 일상 ")).toBeNull();
  const problem = await categoryProblem(env, "미분류");
  expect(problem).toContain('"미분류"');
  expect(problem).toContain("가상국수로그, 일상");
  expect(problem).toContain("list_categories");
});

test("목록을 읽은 적이 없거나 오래됐으면 막지 않는다", async () => {
  const { env } = await setup();
  expect(await categoryProblem(env, "미분류")).toBeNull();

  await listCategories(env, TIMES);
  expect(await categoryProblem(env, "미분류")).not.toBeNull();
  const later = Date.now() + CATEGORY_CACHE_MS + 1_000;
  expect(await categoryProblem(env, "미분류", later)).toBeNull();
  // 다른 블로그의 목록으로는 막지 않는다.
  expect(
    await categoryProblem({ ...env, NAVER_BLOG_ID: "other-blog" }, "미분류"),
  ).toBeNull();
});

test("작성 중인 글 알림이 떠 있으면 목록을 읽지 않고 목록 파일도 남기지 않는다", async () => {
  const { editor, env, jobDir } = await setup();
  editor.popup = "작성 중인 글이 있습니다. 이어서 작성하시겠습니까?";

  const error = await listCategories(env, TIMES).catch((caught) => caught);

  expect(error).toBeInstanceOf(ToolError);
  expect((error as ToolError).code).toBe("NAVER_BLOG_EDITOR_IN_USE");
  expect(await readdir(jobDir).catch(() => [])).toEqual([]);
});

async function callTool(
  server: ReturnType<typeof createServer>,
  name: string,
  args: Record<string, unknown>,
) {
  const client = new Client({ name: "categories-test", version: "1" });
  const [left, right] = InMemoryTransport.createLinkedPair();
  await server.connect(right);
  await client.connect(left);
  try {
    return await client.callTool({ name, arguments: args });
  } finally {
    await client.close();
    await server.close();
  }
}

test("render_draft 는 목록에 없는 카테고리를 problems 로 알리고 html 을 주지 않는다", async () => {
  const { env } = await setup();
  await listCategories(env, TIMES);
  const draft = {
    title: "제목",
    tags: [],
    body: "본문",
    artifact_path: "index.html",
  };

  const unknown = await callTool(createServer(env), "render_draft", {
    ...draft,
    category: "미분류",
  });
  const body = JSON.parse(
    (unknown.content as Array<{ text: string }>)[0]!.text,
  );
  expect(body.html).toBeNull();
  expect(body.problems).toHaveLength(1);
  expect(body.problems[0]).toContain("가상국수로그, 일상");

  const known = await callTool(createServer(env), "render_draft", {
    ...draft,
    category: "일상",
  });
  const ok = JSON.parse((known.content as Array<{ text: string }>)[0]!.text);
  expect(ok.problems).toEqual([]);
  expect(ok.html).not.toBeNull();
});

test("원고 compile 은 목록에 없는 카테고리면 승인 카드 전에 거절한다", async () => {
  const { env } = await setup();
  await listCategories(env, TIMES);
  const request = {
    canonicalDraft: {
      ...canonicalDraftV1Schema.parse(golden.draft),
      blocks: [],
    },
    resolvedAssets: [],
  };
  const compile = (category: string) =>
    callTool(
      createSnapshotServer(env, "compile") as ReturnType<typeof createServer>,
      "compile_content_snapshot",
      { ...request, canonicalDraft: { ...request.canonicalDraft, category } },
    );

  expect((await compile("일상")).isError).not.toBe(true);
  expect((await compile("미분류")).isError).toBe(true);
});

test("덮어쓰기는 카테고리를 바꾼 경우에만 목록과 대조한다", async () => {
  const { env } = await setup();
  await listCategories(env, TIMES);
  const base = { title: "제목", category: "옛날", tags: [], body: "본문" };
  const render = async (category: string) => {
    const result = await callTool(createServer(env), "render_draft", {
      ...base,
      body: "본문을 고친다",
      category,
      base,
      artifact_path: "index.html",
    });
    return JSON.parse((result.content as Array<{ text: string }>)[0]!.text)
      .problems as string[];
  };

  // 그대로 두면 기존 글의 이름이 목록에 없어도 막지 않는다.
  expect(await render("옛날")).toEqual([]);
  expect(await render("미분류")).toHaveLength(1);
  expect(await render("일상")).toEqual([]);
});

test("목록 파일은 임시 파일을 남기지 않는다", async () => {
  const { env, jobDir } = await setup();
  await listCategories(env, TIMES);
  expect(
    (await readdir(jobDir)).filter((name) => name.endsWith(".tmp")),
  ).toEqual([]);
});
