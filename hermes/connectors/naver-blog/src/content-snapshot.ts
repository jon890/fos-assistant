import manifest from "../connector.json";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CallToolRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import { compileContentDraft } from "./content-compiler.ts";
import {
  canonicalDraftV1Schema,
  resolvedAssetSchema,
} from "./content-draft.ts";
import { jsonBytes } from "./content-snapshot-guard.ts";
import {
  DEFAULT_SNAPSHOT_JOB_DEPS,
  type SnapshotJobDeps,
  SnapshotJobError,
  snapshotJobStatus,
  startInputSchema,
  startSnapshotJob,
  statusInputSchema,
} from "./content-snapshot-job.ts";
import { ATTACHMENT_DIR_ENV } from "./draft.ts";
import { renderDraft } from "./render.ts";
import type { Env } from "./session.ts";

export { jsonBytes };
export const SNAPSHOT_FLAG = "--content-snapshot-operation";
export type SnapshotOperation = "compile" | "start" | "status";
const OPERATIONS: readonly SnapshotOperation[] = ["compile", "start", "status"];
const inputSchema = z.strictObject({
  canonicalDraft: canonicalDraftV1Schema,
  resolvedAssets: resolvedAssetSchema.array().max(50),
});

/**
 * 인자는 process 시작자가 정한다. 모델 도구 인자와 env로 private mode를 열지 않는다.
 * 묶음에 든 connector.json 이 capability 를 켠 operation 만 연다. 운영 선언은 compile 하나다.
 */
export function snapshotOperation(
  argv: string[],
  capabilities: Record<SnapshotOperation, boolean> = manifest.snapshot
    .capabilities,
): SnapshotOperation | null {
  const index = argv.indexOf(SNAPSHOT_FLAG);
  if (index < 0) return null;
  const operation = OPERATIONS.find((name) => name === argv[index + 1]);
  if (
    argv.filter((arg) => arg === SNAPSHOT_FLAG).length !== 1 ||
    operation === undefined ||
    capabilities[operation] !== true
  )
    throw new Error("CONTENT_SNAPSHOT_UNAVAILABLE");
  return operation;
}

/** 일반 서버와 worker 진입 전에 private process 인자만 소비한다. */
export async function connectSnapshotServer(argv: string[], env: Env) {
  const operation = snapshotOperation(argv.slice(2));
  if (operation === null) return false;
  if (argv.includes("--worker"))
    throw new Error("CONTENT_SNAPSHOT_UNAVAILABLE");
  await createSnapshotServer(env, operation).connect(
    new StdioServerTransport(),
  );
  return true;
}

/**
 * private process 하나에는 operation 하나의 도구만 등록한다.
 * compile 의 사진은 기존 renderer의 주인 첨부 검사로 확인하며 결과 HTML은 저장하거나 반환하지 않는다.
 */
export function createSnapshotServer(
  env: Env = process.env,
  operation: SnapshotOperation = "compile",
  overrides: Partial<SnapshotJobDeps> = {},
) {
  const deps = { ...DEFAULT_SNAPSHOT_JOB_DEPS, ...overrides };
  const server = new McpServer({
    name: "fos-content-snapshot",
    version: "1.0.0",
  });
  const failed = (error = "invalid_input") => ({
    isError: true,
    content: [{ type: "text" as const, text: JSON.stringify({ error }) }],
  });
  const answer = (value: Record<string, unknown>) => {
    const result = {
      content: [{ type: "text" as const, text: JSON.stringify(value) }],
      structuredContent: value,
    };
    if (jsonBytes(result) > 524288) throw new Error("CONTENT_SNAPSHOT_INVALID");
    return result;
  };
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
      return answer(snapshot);
    } catch {
      return failed();
    }
  };
  /** start·status 는 고정 code 만 돌려준다. 다른 예외의 원문은 버린다. */
  const job =
    (work: (raw: unknown) => Promise<Record<string, unknown>>) =>
    async (raw: unknown) => {
      try {
        return answer(await work(raw));
      } catch (error) {
        return failed(
          error instanceof SnapshotJobError ? error.code : "unavailable",
        );
      }
    };
  const tools = {
    compile: {
      name: manifest.snapshot.compile_tool,
      inputSchema,
      readOnlyHint: true,
      handle: compile,
    },
    start: {
      name: manifest.snapshot.start_tool,
      inputSchema: startInputSchema,
      readOnlyHint: false,
      handle: job((raw) => startSnapshotJob(env, raw, deps)),
    },
    status: {
      name: manifest.snapshot.status_tool,
      inputSchema: statusInputSchema,
      readOnlyHint: true,
      handle: job((raw) => snapshotJobStatus(env, raw)),
    },
  };
  const tool = tools[operation];
  server.registerTool(
    tool.name,
    {
      inputSchema: tool.inputSchema,
      annotations: { readOnlyHint: tool.readOnlyHint },
    },
    tool.handle,
  );
  // SDK 기본 오류는 잘못된 키 이름을 그대로 싣는다. private 요청은 strict 검사도 고정 오류로 감싼다.
  server.server.setRequestHandler(CallToolRequestSchema, (request) =>
    request.params.name === tool.name && !request.params.task
      ? tool.handle(request.params.arguments)
      : failed(),
  );
  return server;
}
