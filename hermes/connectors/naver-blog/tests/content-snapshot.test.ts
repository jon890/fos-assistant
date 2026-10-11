import { afterAll, beforeAll, expect, spyOn, test } from "bun:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { mkdir, mkdtemp, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createServer } from "../src/server.ts";
import {
  createSnapshotServer,
  jsonBytes,
  snapshotOperation,
} from "../src/content-snapshot.ts";
import { compileContentDraft } from "../src/content-compiler.ts";
import {
  canonicalDraftV1Schema,
  resolvedAssetSchema,
  type CanonicalDraftV1,
  type ResolvedAsset,
} from "../src/content-draft.ts";
import golden from "./fixtures/content-snapshot-v1.json";

let root: string;
beforeAll(async () => {
  root = await realpath(await mkdtemp(join(tmpdir(), "snapshot-test-")));
  await mkdir(join(root, "photos"));
  for (const name of ["101.jpg", "102.png"])
    await writeFile(
      join(root, "photos", name),
      name.endsWith("jpg")
        ? new Uint8Array([255, 216, 255, 224])
        : new Uint8Array([137, 80, 78, 71]),
    );
});
afterAll(async () => {
  await rm(root, { recursive: true, force: true });
});

async function connected(privateMode = true, env: Record<string, string> = {}) {
  const client = new Client({ name: "snapshot-test", version: "1" });
  const server = privateMode ? createSnapshotServer(env) : createServer(env);
  const [left, right] = InMemoryTransport.createLinkedPair();
  await server.connect(right);
  await client.connect(left);
  return {
    client,
    close: async () => {
      await client.close();
      await server.close();
    },
  };
}
const input = () => ({
  canonicalDraft: canonicalDraftV1Schema.parse(golden.draft),
  resolvedAssets: resolvedAssetSchema
    .array()
    .parse(golden.assets)
    .map((asset) => ({ ...asset, photoDir: join(root, "photos") })),
});
const empty = (): {
  canonicalDraft: CanonicalDraftV1;
  resolvedAssets: ResolvedAsset[];
} => ({
  canonicalDraft: { ...input().canonicalDraft, blocks: [] },
  resolvedAssets: [],
});

test("공개 목록에는 private 도구가 없으며 env와 모델 인자로 private mode를 켤 수 없다", async () => {
  const { client, close } = await connected(false, {
    FOS_CONTENT_SNAPSHOT_OPERATION: "compile",
  });
  try {
    expect(
      (await client.listTools()).tools.map((tool) => tool.name),
    ).not.toContain("compile_content_snapshot");
    expect(
      (
        await client.callTool({
          name: "compile_content_snapshot",
          arguments: empty(),
        })
      ).isError,
    ).toBe(true);
    expect(snapshotOperation([])).toBeNull();
    expect(snapshotOperation(["--content-snapshot-operation", "compile"])).toBe(
      "compile",
    );
    for (const args of [
      ["--content-snapshot-operation", "start"],
      ["--content-snapshot-operation"],
      [
        "--content-snapshot-operation",
        "compile",
        "--content-snapshot-operation",
        "compile",
      ],
    ])
      expect(() => snapshotOperation(args)).toThrow(
        "CONTENT_SNAPSHOT_UNAVAILABLE",
      );
  } finally {
    await close();
  }
});

test("private 목록은 compile 하나이고 기존 golden 구조·hash·notes·반복과 미사용 asset을 보존한다", async () => {
  const { client, close } = await connected(true, {
    NAVER_BLOG_ATTACHMENT_DIR: root,
  });
  try {
    expect((await client.listTools()).tools.map((tool) => tool.name)).toEqual([
      "compile_content_snapshot",
    ]);
    for (const args of [
      input(),
      { ...input(), resolvedAssets: input().resolvedAssets.reverse() },
      {
        ...input(),
        canonicalDraft: {
          ...input().canonicalDraft,
          blocks: [
            ...input().canonicalDraft.blocks,
            { type: "image" as const, assetId: "101" },
          ],
        },
      },
    ]) {
      const result = await client.callTool({
        name: "compile_content_snapshot",
        arguments: args,
      });
      expect(result.isError).not.toBe(true);
      expect(result.structuredContent).toEqual(
        compileContentDraft(args.canonicalDraft, args.resolvedAssets),
      );
    }
  } finally {
    await close();
  }
});

test("예약 줄·원본 누락·주인 루트 밖의 사진을 경로나 본문 없는 오류로 거절한다", async () => {
  const { client, close } = await connected(true, {
    NAVER_BLOG_ATTACHMENT_DIR: root,
  });
  try {
    for (const args of [
      { ...input(), resolvedAssets: [] },
      {
        ...empty(),
        canonicalDraft: {
          ...empty().canonicalDraft,
          blocks: [{ type: "paragraph", text: "[사진 1: 101.jpg]" }],
        },
      },
      {
        ...input(),
        resolvedAssets: input().resolvedAssets.map((asset) => ({
          ...asset,
          photoDir: "/outside",
        })),
      },
      { ...empty(), mode: "compile" },
    ]) {
      const result = await client.callTool({
        name: "compile_content_snapshot",
        arguments: args,
      });
      expect(result.isError).toBe(true);
      expect(JSON.stringify(result)).not.toContain(root);
    }
  } finally {
    await close();
  }
});

test("UTF-8와 JSON escape를 포함한 원고 64KiB 경계를 실제 직렬화로 검사한다", async () => {
  const { client, close } = await connected();
  try {
    for (const size of [65535, 65536, 65537]) {
      const args = empty();
      const base = jsonBytes({
        ...args.canonicalDraft,
        blocks: [{ type: "paragraph", text: "" }],
      });
      const rest = size - base;
      args.canonicalDraft.blocks = [
        {
          type: "paragraph",
          text: "\u0001".repeat(Math.floor(rest / 6)) + "a".repeat(rest % 6),
        },
      ];
      expect(jsonBytes(args.canonicalDraft)).toBe(size);
      const result = await client.callTool({
        name: "compile_content_snapshot",
        arguments: args,
      });
      expect(result.isError === true).toBe(size > 65536);
    }
    const args = empty();
    args.canonicalDraft.blocks = [
      { type: "paragraph", text: "😀".repeat(17000) },
    ];
    expect(
      (
        await client.callTool({
          name: "compile_content_snapshot",
          arguments: args,
        })
      ).isError,
    ).toBe(true);
  } finally {
    await close();
  }
});

test("50개 고유 asset은 미사용이어도 허용하고 51개·중복·경로·양수 Long 상한은 거절한다", () => {
  const args = empty();
  const asset = input().resolvedAssets[0]!;
  const fifty = Array.from({ length: 50 }, (_, index) => ({
    ...asset,
    assetId: String(index + 1),
  }));
  expect(compileContentDraft(args.canonicalDraft, fifty).assetManifest).toEqual(
    [],
  );
  expect(() =>
    compileContentDraft(args.canonicalDraft, [
      ...fifty,
      { ...asset, assetId: "51" },
    ]),
  ).toThrow();
  expect(() =>
    compileContentDraft(args.canonicalDraft, [asset, asset]),
  ).toThrow();
  for (const changed of [
    { photoDir: "/" + "a".repeat(4096) },
    { fileName: "a".repeat(252) + ".jpg" },
    { assetId: "0" },
    { assetId: "01" },
    { assetId: "9223372036854775808" },
    { assetId: "9".repeat(20) },
  ])
    expect(
      resolvedAssetSchema.safeParse({ ...asset, ...changed }).success,
    ).toBe(false);
  expect(
    resolvedAssetSchema.safeParse({
      ...asset,
      photoDir: "/" + "a".repeat(4095),
      fileName: "a".repeat(251) + ".jpg",
      assetId: "9223372036854775807",
    }).success,
  ).toBe(true);
});

test("compile은 provider 호출과 worker를 만들지 않고 잘못된 키도 고정 오류로 가린다", async () => {
  const blockedFetch = Object.assign(
    async () => {
      throw new Error("외부 호출 금지");
    },
    {
      preconnect: () => {
        throw new Error("외부 호출 금지");
      },
    },
  );
  const fetch = spyOn(globalThis, "fetch").mockImplementation(blockedFetch);
  const spawn = spyOn(Bun, "spawn").mockImplementation(() => {
    throw new Error("worker 생성 금지");
  });
  const { client, close } = await connected(true, {
    NAVER_BLOG_ATTACHMENT_DIR: root,
    NAVER_BLOG_BROWSER_URL: "http://unreachable.test/blocked",
    NAVER_BLOG_ID: "synthetic_only",
  });
  try {
    expect(
      (
        await client.callTool({
          name: "compile_content_snapshot",
          arguments: input(),
        })
      ).isError,
    ).not.toBe(true);
    const result = await client.callTool({
      name: "compile_content_snapshot",
      arguments: {
        ...empty(),
        "private-key-that-must-not-leak": "body-that-must-not-leak",
      },
    });
    expect(result).toEqual({
      isError: true,
      content: [{ type: "text", text: '{"error":"invalid_input"}' }],
    });
    expect(fetch).not.toHaveBeenCalled();
    expect(spawn).not.toHaveBeenCalled();
  } finally {
    await close();
    fetch.mockRestore();
    spawn.mockRestore();
  }
});
