import { afterEach, beforeEach, expect, test } from "bun:test";
import { mkdir, mkdtemp, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { PHOTO_DIRECTORY_PROBLEM, photoProblem } from "../src/draft.ts";
import { renderDraft, type RenderInput } from "../src/render.ts";
import { createServer } from "../src/server.ts";

const JPEG = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0, 0x10, 0x4a, 0x46, 0x49, 0x46, 0, 1]);

/** 바인딩 설치가 `NAVER_BLOG_ATTACHMENT_DIR` 로 주는 주인의 첨부 디렉터리. 사진 디렉터리는 그 아래에 둔다. */
let attachmentDir: string;
let photoDir: string;

beforeEach(async () => {
  // macOS 의 임시 디렉터리는 링크 아래에 있다. 커넥터가 링크를 거절하므로 실제 경로를 쓴다.
  attachmentDir = await realpath(await mkdtemp(join(tmpdir(), "naver-blog-render-")));
  photoDir = join(attachmentDir, "conversation");
  await mkdir(photoDir);
  await writeFile(join(photoDir, "101.jpg"), JPEG);
  await writeFile(join(photoDir, "photo.jpg"), JPEG);
});

afterEach(async () => {
  await rm(attachmentDir, { recursive: true, force: true });
});

const BODY = [
  "[스티커: ogq_abc123-4]",
  "안녕하세요 가상블로그입니다",
  "",
  "[사진 1: 101.jpg]",
  "간판부터 정겨운 느낌",
  "[사진 2: photo.jpg]",
  "[스티커: sticker_hello]",
  "[지도: 가상국수 | 서울특별시 가상구 예시로 1]",
  "[스티커: ogq_abc123-4]",
].join("\n");

const input = (extra: Partial<RenderInput> = {}): RenderInput => ({
  title: "동네 <b>국숫집</b>",
  category: "맛집",
  tags: ["가상국수", "국수"],
  body: BODY,
  photo_dir: photoDir,
  photo_notes: { "1": "가게 앞 간판", "2": "비빔국수 한 그릇" },
  artifact_path: "noodle-review/index.html",
  ...extra,
});

test("미리보기는 첨부 이름의 사진만 첨부 주소로 부르고 설명을 붙인다", async () => {
  const result = await renderDraft(input(), attachmentDir);

  expect(result.problems).toEqual([]);
  const html = result.html!;
  expect(html).toContain('<img src="../../attachments/101" alt="1번째 사진" loading="lazy">');
  expect(html).toContain("가게 앞 간판");
  expect(html).toContain("2번째 사진");
  expect(html).toContain("비빔국수 한 그릇");
  expect(html).not.toContain("photo.jpg");
  expect(html.match(/<img src="\.\.\/\.\.\/attachments\//g)).toHaveLength(1);
});

test("미리보기는 스크립트와 외부 자원 없이 제목을 이스케이프하고 내부 경로를 담지 않는다", async () => {
  const { html } = await renderDraft(input(), attachmentDir);

  expect(html).not.toContain("<script");
  expect(html).not.toMatch(/<link|@import|url\(|https?:\/\//);
  expect(html).toContain("동네 &lt;b&gt;국숫집&lt;/b&gt;");
  expect(html).not.toContain("<b>");
  expect(html).not.toContain(photoDir);
  expect(html).toContain("#가상국수");
  expect(html).toContain("width:390px");
});

test("미리보기는 글 줄과 빈 줄, 지도 카드를 차례로 그린다", async () => {
  const { html } = await renderDraft(input(), attachmentDir);

  const order = [
    "안녕하세요 가상블로그입니다",
    '<p class="blank"></p>',
    "../../attachments/101",
    "간판부터 정겨운 느낌",
    "<strong>가상국수</strong><span>서울특별시 가상구 예시로 1</span>",
  ].map((part) => html!.indexOf(part));
  expect(order.every((index) => index >= 0)).toBe(true);
  expect([...order].sort((a, b) => a - b)).toEqual(order);
});

test("OGQ 스티커는 같은 폴더의 그림을 부르고 그림 주소는 코드마다 한 번만 담는다", async () => {
  const { html, assets } = await renderDraft(input(), attachmentDir);

  expect(html!.match(/<img class="sticker" src="stickers\/ogq_abc123-4\.png"/g)).toHaveLength(2);
  expect(assets).toEqual([
    {
      path: "noodle-review/stickers/ogq_abc123-4.png",
      source_url: "https://storep-phinf.pstatic.net/ogq_abc123/original_4.png?type=p100_100",
    },
  ]);
});

test("OGQ 모양이 아닌 스티커는 이름표로 그리고 assets 에 넣지 않는다", async () => {
  const { html, assets } = await renderDraft(input(), attachmentDir);

  expect(html).toContain("스티커: sticker_hello");
  expect(html).not.toContain("stickers/sticker_hello");
  expect(assets!.some((asset) => asset.path.includes("sticker_hello"))).toBe(false);
});

test("package 는 지시 줄을 자리 표시로 바꾸고 태그를 쉼표로 잇는다", async () => {
  const { problems, html, assets } = await renderDraft(input({ kind: "package" }), attachmentDir);

  expect(problems).toEqual([]);
  expect(assets).toEqual([]);
  expect(html).toContain("[1번째 사진 자리]");
  expect(html).toContain("[2번째 사진 자리]");
  expect(html).toContain("[스티커 ogq_abc123-4]");
  expect(html).toContain("[지도 가상국수 / 서울특별시 가상구 예시로 1]");
  expect(html).toContain("가상국수, 국수");
  expect(html).not.toContain("[사진 1: 101.jpg]");
  expect(html).toContain('<img src="../../attachments/101" alt="1번째 사진" loading="lazy">');
  expect(html).not.toContain("<script");
  expect(html).not.toContain(photoDir);
});

test.each([
  ["초안 계약 위반", { title: "" }],
  ["없는 사진 파일", { body: "[사진 1: 999.jpg]" }],
  ["깊은 artifact_path", { artifact_path: "a/b/index.html" }],
  ["점으로 시작하는 artifact_path", { artifact_path: "../index.html" }],
  ["사진 번호가 아닌 photo_notes 키", { photo_notes: { first: "설명" } }],
  ["301자 photo_notes", { photo_notes: { "1": "가".repeat(301) } }],
])("%s 이면 오류 없이 problems 와 null html 을 돌려준다", async (_, extra) => {
  const result = await renderDraft(input(extra), attachmentDir);

  expect(result.html).toBeNull();
  expect(result.assets).toEqual([]);
  expect(result.problems.length).toBeGreaterThan(0);
  for (const problem of result.problems) expect(problem).not.toContain(photoDir);
});

test("300자 photo_notes 는 받는다", async () => {
  const result = await renderDraft(input({ photo_notes: { "1": "가".repeat(300) } }), attachmentDir);

  expect(result.problems).toEqual([]);
});

test("render_draft 도구는 kind 가 없으면 미리보기를 결과 글로 돌려준다", async () => {
  const client = new Client({ name: "naver-blog-test", version: "1.0.0" });
  const server = createServer({ NAVER_BLOG_ATTACHMENT_DIR: attachmentDir });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  const { kind: _, ...arguments_ } = input();
  try {
    await server.connect(serverTransport);
    await client.connect(clientTransport);
    const result = await client.callTool({ name: "render_draft", arguments: arguments_ });
    const text = (result.content as Array<{ text: string }>)[0]!.text;

    expect(result.isError).not.toBe(true);
    expect(text).not.toContain(photoDir);
    const body = JSON.parse(text);
    expect(body.problems).toEqual([]);
    expect(body.html).toContain("width:390px");
    expect(body.assets).toHaveLength(1);
  } finally {
    await client.close();
    await server.close();
  }
});

/** render_draft 도구를 부르고 결과의 problems 를 돌려준다. */
async function renderProblems(env: Record<string, string>, extra: Partial<RenderInput>) {
  const client = new Client({ name: "naver-blog-test", version: "1.0.0" });
  const server = createServer(env);
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  try {
    await server.connect(serverTransport);
    await client.connect(clientTransport);
    const result = await client.callTool({ name: "render_draft", arguments: input(extra) });
    const text = (result.content as Array<{ text: string }>)[0]!.text;
    expect(result.isError).not.toBe(true);
    expect(text).not.toContain(attachmentDir);
    return JSON.parse(text).problems as string[];
  } finally {
    await client.close();
    await server.close();
  }
}

test("render_draft 도구는 첨부 디렉터리 밖, 없는 디렉터리, 빈 env 를 같은 문장으로 알린다", async () => {
  const outside = await realpath(await mkdtemp(join(tmpdir(), "naver-blog-outside-")));
  try {
    await writeFile(join(outside, "101.jpg"), JPEG);
    await writeFile(join(outside, "photo.jpg"), JPEG);
    const env = { NAVER_BLOG_ATTACHMENT_DIR: attachmentDir };

    const answers = [
      await renderProblems(env, { photo_dir: outside }),
      await renderProblems(env, { photo_dir: join(attachmentDir, "missing") }),
      await renderProblems({}, {}),
    ];

    expect(answers).toEqual([
      [PHOTO_DIRECTORY_PROBLEM],
      [PHOTO_DIRECTORY_PROBLEM],
      [PHOTO_DIRECTORY_PROBLEM],
    ]);
  } finally {
    await rm(outside, { recursive: true, force: true });
  }
});

test("render_draft 도구는 없는 사진과 서명이 틀린 사진을 같은 문장으로 알린다", async () => {
  await writeFile(join(photoDir, "fake.jpg"), new TextEncoder().encode("not a jpeg"));
  const env = { NAVER_BLOG_ATTACHMENT_DIR: attachmentDir };

  const problems = await renderProblems(env, { body: "[사진 1: missing.jpg]\n[사진 2: fake.jpg]" });

  expect(problems).toEqual([photoProblem(1), photoProblem(2)]);
  expect(photoProblem(1)).toBe("1번째 사진 자리의 사진을 쓸 수 없습니다.");
});
