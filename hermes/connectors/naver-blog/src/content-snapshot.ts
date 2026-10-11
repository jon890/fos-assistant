import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CallToolRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import { compileContentDraft } from "./content-compiler.ts";
import {
  canonicalDraftV1Schema,
  resolvedAssetSchema,
} from "./content-draft.ts";
import { ATTACHMENT_DIR_ENV } from "./draft.ts";
import { renderDraft } from "./render.ts";
import type { Env } from "./session.ts";

export const SNAPSHOT_FLAG = "--content-snapshot-operation";
export const jsonBytes = (value: unknown) =>
  Buffer.byteLength(JSON.stringify(value), "utf8");
const inputSchema = z.strictObject({
  canonicalDraft: canonicalDraftV1Schema,
  resolvedAssets: resolvedAssetSchema.array().max(50),
});

/** 인자는 process 시작자가 정한다. 모델 도구 인자와 env로 private mode를 열지 않는다. */
export function snapshotOperation(argv: string[]): "compile" | null {
  const index = argv.indexOf(SNAPSHOT_FLAG);
  if (index < 0) return null;
  if (
    argv.filter((arg) => arg === SNAPSHOT_FLAG).length !== 1 ||
    argv[index + 1] !== "compile"
  )
    throw new Error("CONTENT_SNAPSHOT_UNAVAILABLE");
  return "compile";
}

/** 일반 서버와 worker 진입 전에 private process 인자만 소비한다. */
export async function connectSnapshotServer(argv: string[], env: Env) {
  if (snapshotOperation(argv.slice(2)) === null) return false;
  if (argv.includes("--worker"))
    throw new Error("CONTENT_SNAPSHOT_UNAVAILABLE");
  await createSnapshotServer(env).connect(new StdioServerTransport());
  return true;
}

/** 사진은 기존 renderer의 주인 첨부 검사로 확인하며 결과 HTML은 저장하거나 반환하지 않는다. */
export function createSnapshotServer(env: Env = process.env) {
  const server = new McpServer({
    name: "fos-content-snapshot",
    version: "1.0.0",
  });
  const failed = () => ({
    isError: true,
    content: [{ type: "text" as const, text: '{"error":"invalid_input"}' }],
  });
  const compile = async (raw: unknown) => {
    try {
      const input = inputSchema.parse(raw);
      if (jsonBytes(input) > 524288 || jsonBytes(input.canonicalDraft) > 65536)
        throw new Error("CONTENT_SNAPSHOT_INVALID");
      const snapshot = compileContentDraft(
        input.canonicalDraft,
        input.resolvedAssets,
      );
      if (jsonBytes(snapshot) > 262144)
        throw new Error("CONTENT_SNAPSHOT_INVALID");
      const rendered = await renderDraft(
        {
          ...snapshot.payload,
          photo_notes: snapshot.photoNotes,
          artifact_path: "index.html",
        },
        env[ATTACHMENT_DIR_ENV],
      );
      if (rendered.problems.length) throw new Error("CONTENT_SNAPSHOT_INVALID");
      const result = {
        content: [{ type: "text" as const, text: JSON.stringify(snapshot) }],
        structuredContent: snapshot,
      };
      if (jsonBytes(result) > 524288)
        throw new Error("CONTENT_SNAPSHOT_INVALID");
      return result;
    } catch {
      return failed();
    }
  };
  server.registerTool(
    "compile_content_snapshot",
    {
      inputSchema,
      annotations: { readOnlyHint: true },
    },
    compile,
  );
  // SDK 기본 오류는 잘못된 키 이름을 그대로 싣는다. private 요청은 strict 검사도 고정 오류로 감싼다.
  server.server.setRequestHandler(CallToolRequestSchema, (request) =>
    request.params.name === "compile_content_snapshot" && !request.params.task
      ? compile(request.params.arguments)
      : failed(),
  );
  return server;
}
