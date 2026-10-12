import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { afterEach, expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { draftRevision } from "../src/changes.ts";
import {
  documentPhotos,
  documentToDraft,
  draftToDocument,
} from "../src/document.ts";
import { EDITOR_METHODS, FakeCdp, FakeEditor } from "./fake-cdp.ts";

const paragraph = (value: string) => ({
  nodes: [{ value }],
  "@ctype": "paragraph",
});
const title = { "@ctype": "documentTitle", title: [paragraph("합성 글")] };
const text = (value: string) => ({
  "@ctype": "text",
  value: [paragraph(value)],
});
// 실제 관측의 칸과 타입을 합성값으로 재현한다. 원본 계정과 사진 값은 쓰지 않는다.
const image = (id = "SE-synthetic-a", fileName: unknown = "alpha.jpg") => ({
  "@ctype": "image",
  id,
  fileName,
  layout: "default",
  src: "https://example.com/hidden.jpg",
  internalResource: true,
  represent: false,
  path: "/synthetic/hidden.jpg",
  domain: "https://example.com",
  fileSize: 1234,
  width: 800,
  height: 600,
  originalWidth: 1600,
  originalHeight: 1200,
  widthPercentage: 100,
  caption: null,
  location: { latitude: 0, longitude: 0, "@ctype": "location" },
  origin: { srcFrom: "local", "@ctype": "imageOrigin" },
  format: "normal",
  displayFormat: "normal",
  imageLoaded: true,
  contentMode: "fit",
  ai: false,
});
const sample = () => ({
  document: {
    components: [
      title,
      text("앞"),
      image(),
      text("사이"),
      image("SE-synthetic-b", "beta.png"),
      text("뒤"),
    ],
  },
});

test("문단 사이 직접 image만 투영하고 본문과 기존 구성요소를 바꾸지 않는다", () => {
  const data = sample();
  const before = structuredClone(data);
  expect(documentPhotos(data)).toEqual({
    photos: [
      {
        position: 1,
        componentIndex: 2,
        componentId: "SE-synthetic-a",
        fileName: "alpha.jpg",
      },
      {
        position: 2,
        componentIndex: 4,
        componentId: "SE-synthetic-b",
        fileName: "beta.png",
      },
    ],
    photo_count: 2,
    photos_truncated: false,
  });
  expect(documentToDraft(data)).toEqual({
    title: "합성 글",
    body: "앞\n[기존 사진 1]\n사이\n[기존 사진 2]\n뒤",
    existing: [
      { line: "[기존 사진 1]", index: 2 },
      { line: "[기존 사진 2]", index: 4 },
    ],
  });
  expect(data).toEqual(before);
});

test("덮어쓰기의 사진 재배열과 제거 뒤에도 실제 현재 순서를 읽는다", () => {
  const data = sample();
  const reordered = draftToDocument(
    data,
    "합성 글",
    "앞\n[기존 사진 2]\n사이\n[기존 사진 1]\n뒤",
  );
  expect(documentPhotos(reordered).photos.map((p) => p.fileName)).toEqual([
    "beta.png",
    "alpha.jpg",
  ]);
  expect(
    reordered.document!.components!.filter((c) => c["@ctype"] === "image"),
  ).toEqual([data.document.components[4], data.document.components[2]]);
  const removed = draftToDocument(data, "합성 글", "앞\n[기존 사진 2]\n뒤");
  expect(documentPhotos(removed).photos).toEqual([
    {
      position: 1,
      componentIndex: 2,
      componentId: "SE-synthetic-b",
      fileName: "beta.png",
    },
  ]);
});

test.each(
  [
    undefined,
    null,
    12,
    {},
    [],
    "",
    ".",
    "..",
    "/tmp/photo.jpg",
    "C:\\photo.jpg",
    "a/b.jpg",
    "https://example.com/a.jpg",
    "file:a.jpg",
    "a\u0000.jpg",
    "a\n.jpg",
    "a\u007f.jpg",
    "a\u0085.jpg",
    "a\u202e.jpg",
    "a\u2028.jpg",
    "a\ud800.jpg",
    " a.jpg",
    "a.jpg ",
    "a".repeat(256),
  ].map((value) => [value]),
)("잘못된 파일명 %j는 null로 두고 본문 읽기를 유지한다", (value) => {
  const data = {
    document: { components: [title, { ...image(), fileName: value }] },
  };
  expect(documentPhotos(data).photos[0]!.fileName).toBeNull();
  expect(documentToDraft(data).body).toBe("[기존 사진 1]");
});

test.each(["가족 사진.jpg", "a".repeat(255), "alpha.jpg"])(
  "유효한 파일명 %s는 그대로 보존한다",
  (value) => {
    expect(
      documentPhotos({ document: { components: [image("SE-a", value)] } })
        .photos[0]!.fileName,
    ).toBe(value);
  },
);

test.each([
  undefined,
  null,
  12,
  {},
  "",
  "a".repeat(201),
  "https://example.com/id",
  "a/b",
  "a\n",
  "a\u202e",
])("잘못된 component id %j는 null이다", (id) => {
  expect(
    documentPhotos({ document: { components: [{ ...image(), id }] } })
      .photos[0]!.componentId,
  ).toBeNull();
});

test("중복 파일명은 합치지 않고 URL에만 있는 파일명과 중첩 이미지는 추정하지 않는다", () => {
  const data = {
    document: {
      components: [
        title,
        image(),
        image("SE-b"),
        { "@ctype": "image", src: "https://example.com/alias.jpg" },
        { "@ctype": "imageGroup", images: [image()] },
      ],
    },
  };
  expect(documentPhotos(data).photos.map((p) => p.fileName)).toEqual([
    "alpha.jpg",
    "alpha.jpg",
    null,
  ]);
  expect(documentPhotos(data).photo_count).toBe(3);
  expect(Object.keys(documentPhotos(data).photos[0]!).sort()).toEqual([
    "componentId",
    "componentIndex",
    "fileName",
    "position",
  ]);
});

test.each([0, 50, 51])(
  "사진 %i개의 실제 개수와 최대 50개 응답을 구분한다",
  (count) => {
    const data = {
      document: {
        components: [
          title,
          ...Array.from({ length: count }, (_, i) => image(`SE-${i}`)),
        ],
      },
    };
    const result = documentPhotos(data);
    expect(result.photo_count).toBe(count);
    expect(result.photos.length).toBe(Math.min(count, 50));
    expect(result.photos_truncated).toBe(count > 50);
    if (count)
      expect(result.photos.at(-1)).toMatchObject({
        position: Math.min(count, 50),
        componentIndex: Math.min(count, 50),
      });
    expect(documentToDraft(data).existing.length).toBe(count);
  },
);

const cleanups: Array<() => Promise<void> | void> = [];
afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

test("실제 SDK read_draft가 편집기 사진을 반환하고 메타데이터 변경은 기존 revision에 영향을 주지 않는다", async () => {
  const cdp = new FakeCdp({ allowed: EDITOR_METHODS });
  cleanups.push(() => cdp.stop());
  const editor = new FakeEditor();
  const fixture = sample();
  editor.drafts = [
    {
      logNo: 224000000004,
      title: "합성 글",
      modiDate: Date.UTC(2026, 0, 1),
      components: fixture.document.components,
      category: "합성",
      tags: [],
    },
  ];
  editor.install(cdp);
  const dir = await mkdtemp(join(tmpdir(), "naver-photo-metadata-"));
  cleanups.push(() => rm(dir, { recursive: true, force: true }));
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: ["--no-env-file", join(import.meta.dir, "../dist/naver-blog-mcp.js")],
    env: {
      NAVER_BLOG_BROWSER_URL: cdp.url,
      NAVER_BLOG_ID: "example-blog",
      NAVER_BLOG_JOB_DIR: join(dir, "jobs"),
    },
    stderr: "pipe",
  });
  const client = new Client({ name: "photo-metadata-test", version: "1.0.0" });
  try {
    await client.connect(transport);
    const call = async () => {
      // 대역은 탭마다 편집기를 새로 만들지 않아 새 탭의 빈 편집기로 되돌린다.
      editor.document = null;
      editor.documentId = "";
      editor.loaded = null;
      const result = await client.callTool({
        name: "read_draft",
        arguments: { draft_id: "224000000004" },
      });
      expect(result.isError).not.toBe(true);
      return JSON.parse((result.content as Array<{ text: string }>)[0]!.text);
    };
    const draft = {
      title: "합성 글",
      category: "합성",
      tags: [],
      body: documentToDraft(fixture).body,
    };
    const first = await call();
    expect(first).toEqual({
      draft_id: "224000000004",
      revision: draftRevision(draft),
      ...draft,
      ...documentPhotos(fixture),
      autosave_requests: 0,
      autosave_check: {
        count_before: 1,
        count_after: 1,
        saved_at_before: "2026-01-01T00:00:00.000Z",
        saved_at_after: "2026-01-01T00:00:00.000Z",
        changed: false,
      },
    });
    for (const p of first.photos)
      expect(Object.keys(p).sort()).toEqual([
        "componentId",
        "componentIndex",
        "fileName",
        "position",
      ]);
    expect(JSON.stringify(first)).not.toContain("example.com");
    fixture.document.components[2] = image("SE-changed", "changed.jpg");
    const second = await call();
    expect(second.photos[0]).toMatchObject({
      componentId: "SE-changed",
      fileName: "changed.jpg",
    });
    expect(second.revision).toBe(first.revision);
    expect(second.body).toBe(first.body);
    editor.drafts[0]!.components = [
      title,
      ...Array.from({ length: 51 }, (_, i) =>
        image(`SE-${i}`, i === 0 ? null : "same.jpg"),
      ),
    ];
    const bounded = await call();
    expect(bounded.photo_count).toBe(51);
    expect(bounded.photos_truncated).toBe(true);
    expect(bounded.photos).toHaveLength(50);
    expect(bounded.photos[0].fileName).toBeNull();
    expect(bounded.photos.at(-1)).toEqual({
      position: 50,
      componentIndex: 50,
      componentId: "SE-49",
      fileName: "same.jpg",
    });
    expect(bounded.body.split("\n")).toHaveLength(51);
    expect(cdp.violations).toEqual([]);
    expect(cdp.targetIds()).toEqual([]);
    expect(editor.scripts.some((script) => script.includes('=== "저장"'))).toBe(
      false,
    );
  } finally {
    await client.close();
  }
}, 15_000);
