import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { rm } from "node:fs/promises";
import { z } from "zod";
import { PROXY_ENVIRONMENT_KEYS } from "./cdp.ts";
import {
  ATTACHMENT_DIR_ENV,
  checkPhotoFiles,
  draftShape,
  type DraftInput,
  validateDraft,
} from "./draft.ts";
import { listDrafts, readDraft } from "./drafts.ts";
import { DRAFT_ID_PATTERN } from "./editor/drafts.ts";
import type { OverwriteInput } from "./editor/overwrite.ts";
import { guard, ToolError } from "./errors.ts";
import {
  acquireLock,
  createState,
  finishStale,
  inputFile,
  JOB_ID_PATTERN,
  jobAlive,
  openJobDir,
  publicState,
  readState,
  releaseLock,
  stateFile,
  writeInput,
} from "./jobs.ts";
import { cardWouldMask } from "./card-mask.ts";
import { CHANGES_MAX, overwriteContentShape, validateOverwrite } from "./overwrite-draft.ts";
import { renderDraft, renderShape, type RenderInput } from "./render.ts";
import { readConnection, sessionStatus, type Env } from "./session.ts";
import { runWorker } from "./worker.ts";

const MINIMUM_BUN_VERSION = [1, 3, 14] as const;

/** 프록시가 상속된 Bun 은 시작할 때 연결 대상을 캐시하므로 깨끗한 자식으로 다시 시작한다. */
function hasProxyEnvironment(env: Env) {
  return PROXY_ENVIRONMENT_KEYS.some((key) => Boolean(env[key]));
}

function proxyFreeEnvironment(env: Env): Env {
  const cleaned = { ...env };
  for (const key of PROXY_ENVIRONMENT_KEYS) delete cleaned[key];
  return cleaned;
}

/** 최소 Bun 1.3.14 release 이상인지 SemVer 숫자로 비교한다. */
export function isSupportedBunVersion(version: string) {
  const match =
    /^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/.exec(
      version,
    );
  if (!match) return false;
  const parts = [Number(match[1]), Number(match[2]), Number(match[3])];
  for (const [index, minimum] of MINIMUM_BUN_VERSION.entries()) {
    if (parts[index] !== minimum) return parts[index]! > minimum;
  }
  return match[4] === undefined;
}

export type ServerDeps = {
  /** 작업 프로세스로 다시 실행할 묶음 파일. 기본값은 지금 실행 중인 파일이다. */
  workerEntry: string;
  /** 작업 프로세스가 시작을 알리기를 기다리는 시간. */
  startWaitMs: number;
  /** `save_draft` 와 `overwrite_draft` 가 작업을 띄우기 전에 브라우저와 로그인을 확인하는 함수. */
  checkSession: typeof sessionStatus;
};

const DEFAULT_SERVER_DEPS: ServerDeps = {
  workerEntry: process.argv[1] ?? "",
  startWaitMs: 5_000,
  checkSession: sessionStatus,
};

/**
 * 작업 프로세스에 넘기는 env. 연결 칸 값은 파일에 쓰지 않고 env 로만 넘긴다.
 * 작업 프로세스가 사진을 읽을 때도 같은 첨부 디렉터리로 다시 검사하도록 그 env 를 함께 넘긴다.
 */
function workerEnvironment(env: Env) {
  const keys = ["PATH", "HOME", "NAVER_BLOG_BROWSER_URL", "NAVER_BLOG_ID", ATTACHMENT_DIR_ENV];
  if (env.NAVER_BLOG_JOB_DIR) keys.push("NAVER_BLOG_JOB_DIR");
  const picked: Record<string, string> = {};
  for (const key of keys) {
    const value = env[key];
    if (value !== undefined) picked[key] = value;
  }
  return picked;
}

/**
 * 브라우저와 로그인을 확인한 뒤 잠금을 잡고 입력 파일을 써서 분리된 작업 프로세스를 띄운다.
 * 작업 프로세스는 새 세션으로 떠서 대시보드가 이 서버의 프로세스 묶음을 정리해도 남는다.
 * 띄우기 전에 실패하면 잠금과 작업 파일을 지운다.
 */
async function startJob(env: Env, input: Record<string, unknown>, deps: ServerDeps) {
  const { blogId } = readConnection(env);
  // 중계가 꺼진 브라우저를 켜는 데 30초까지 걸려 확인 도구의 8초보다 길게 기다린다.
  await deps.checkSession(env, { timeoutMs: 45_000 });
  const dir = await openJobDir(env);
  const jobId = randomUUID();
  await acquireLock(dir, blogId, jobId);
  const path = stateFile(dir, jobId);
  try {
    await createState(dir, {
      job_id: jobId,
      status: "running",
      stage: "queued",
      save_clicked: false,
      started_at: new Date().toISOString(),
      finished_at: null,
      result: null,
      error: null,
      pid: null,
      heartbeat_at: null,
    });
    await writeInput(dir, jobId, input);
    const child = spawn(process.execPath, [deps.workerEntry, "--worker", path], {
      detached: true,
      stdio: "ignore",
      env: workerEnvironment(env),
    });
    // 띄운 뒤의 오류는 시작 확인이 시간 초과로 알린다.
    child.on("error", () => {});
    child.unref();
  } catch {
    await releaseLock(dir, jobId);
    await rm(path, { force: true });
    await rm(inputFile(dir, jobId), { force: true });
    throw new ToolError("NAVER_BLOG_UNAVAILABLE");
  }
  const deadline = Date.now() + deps.startWaitMs;
  for (;;) {
    const state = await readState(dir, jobId);
    if (state?.pid != null && (state.stage !== "queued" || state.status !== "running"))
      return { job_id: jobId, status: "running" };
    if (Date.now() >= deadline) throw new ToolError("NAVER_BLOG_START_UNKNOWN");
    await Bun.sleep(Math.min(100, Math.max(1, deadline - Date.now())));
  }
}

/** 초안과 사진을 검사한 뒤 새 글 저장 작업을 띄운다. 입력 파일에는 `kind` 를 두지 않는다. */
async function saveDraft(env: Env, input: DraftInput, deps: ServerDeps) {
  if (validateDraft(input).length) throw new ToolError("NAVER_BLOG_INVALID_INPUT");
  if ((await checkPhotoFiles(input, env[ATTACHMENT_DIR_ENV])).length)
    throw new ToolError("NAVER_BLOG_PHOTO_INVALID");
  return startJob(
    env,
    {
      title: input.title,
      category: input.category,
      tags: input.tags,
      body: input.body,
      ...(input.photo_dir === undefined ? {} : { photo_dir: input.photo_dir }),
    },
    deps,
  );
}

/** 고친 글의 모양을 검사한 뒤 `kind: "overwrite"` 입력 파일로 덮어쓰기 작업을 띄운다. */
async function overwriteDraft(env: Env, input: OverwriteInput, deps: ServerDeps) {
  const { draft_id, revision, changes, title, category, tags, body } = input;
  if (validateOverwrite({ title, category, tags, body }).length || cardWouldMask(changes))
    throw new ToolError("NAVER_BLOG_INVALID_INPUT");
  return startJob(
    env,
    { kind: "overwrite", draft_id, revision, changes, title, category, tags, body },
    deps,
  );
}

/**
 * 작업 상태를 읽고, 돌고 있으면 1초마다 다시 읽어 끝나거나 `waitSeconds` 가 차면 돌려준다.
 * 돌고 있다는 작업의 프로세스가 사라졌거나 멈췄으면 묵은 작업으로 끝내고 잠금을 푼다.
 */
async function draftJob(env: Env, jobId: string, waitSeconds: number) {
  const dir = await openJobDir(env);
  const deadline = Date.now() + waitSeconds * 1_000;
  for (;;) {
    let state = await readState(dir, jobId);
    if (!state) throw new ToolError("NAVER_BLOG_JOB_NOT_FOUND");
    if (state.status === "running" && !jobAlive(state, Date.parse(state.started_at))) {
      state = (await finishStale(dir, state)) ?? state;
      await releaseLock(dir, jobId);
    }
    if (state.status !== "running" || Date.now() >= deadline) return publicState(state);
    await Bun.sleep(Math.min(1_000, Math.max(1, deadline - Date.now())));
  }
}

/** 도구를 등록한 서버. 시험은 이 서버를 InMemoryTransport 로 부른다. */
export function createServer(env: Env = process.env, overrides: Partial<ServerDeps> = {}) {
  const deps = { ...DEFAULT_SERVER_DEPS, ...overrides };
  const server = new McpServer({ name: "fos-naver-blog", version: "1.0.0" });
  server.registerTool(
    "session_status",
    {
      description:
        "내 브라우저에 붙는지와 네이버 로그인 쿠키가 있는지 확인합니다. 탭을 열지 않습니다.",
      inputSchema: {},
      annotations: { readOnlyHint: true },
    },
    () => guard(() => sessionStatus(env)),
  );
  server.registerTool(
    "list_drafts",
    {
      description:
        "네이버 블로그의 임시저장 글 목록(draft_id, 제목, 고친 시각)을 읽습니다. 글을 바꾸지 않습니다.",
      inputSchema: {},
      annotations: { readOnlyHint: true },
    },
    () => guard(() => listDrafts(env)),
  );
  server.registerTool(
    "read_draft",
    {
      description:
        "사용자의 매번 승인 후 임시저장 글을 편집기에 불러와 제목, 카테고리, 태그, 본문과 지문(revision)을 읽습니다. 불러오면서 네이버 자동저장이 발생할 수 있습니다. 저장 단추와 발행 단추는 누르지 않습니다. 본문에서 사진, 스티커, 지도는 [기존 사진 1] 같은 줄입니다.",
      inputSchema: {
        draft_id: z.string().regex(DRAFT_ID_PATTERN).describe("list_drafts 가 돌려준 draft_id"),
      },
      annotations: { readOnlyHint: false },
    },
    ({ draft_id }) => guard(() => readDraft(env, draft_id)),
  );
  server.registerTool(
    "render_draft",
    {
      description:
        "초안을 검사하고 모바일 미리보기나 수동 등록용 HTML 을 돌려줍니다. 아무것도 저장하지 않습니다. problems 가 있으면 html 이 null 입니다.",
      inputSchema: renderShape,
      annotations: { readOnlyHint: true },
    },
    (input) => guard(() => renderDraft(input as RenderInput, env[ATTACHMENT_DIR_ENV])),
  );
  server.registerTool(
    "save_draft",
    {
      description:
        "초안을 검사하고 브라우저와 로그인을 확인한 뒤 네이버 블로그 임시저장 작업을 시작합니다. 발행하지 않습니다. 결과는 draft_job 으로 읽습니다.",
      inputSchema: draftShape,
    },
    (input) => guard(() => saveDraft(env, input as DraftInput, deps)),
  );
  server.registerTool(
    "overwrite_draft",
    {
      description:
        "read_draft 로 읽고 render_draft 의 base 로 미리 본 임시저장 글을 고칩니다. 고치기 전에 원래 글을 [덮어쓰기 전 원본] 사본으로 남깁니다. 발행하지 않습니다. 결과는 draft_job 으로 읽습니다.",
      inputSchema: {
        draft_id: z.string().regex(DRAFT_ID_PATTERN).describe("read_draft 로 읽은 글의 draft_id"),
        revision: z
          .string()
          .regex(/^[0-9a-f]{16}$/)
          .describe("render_draft 가 돌려준 base_revision"),
        changes: z
          .string()
          .min(1)
          .max(CHANGES_MAX)
          .describe("render_draft 가 돌려준 changes 그대로"),
        ...overwriteContentShape,
      },
    },
    (input) => guard(() => overwriteDraft(env, input as OverwriteInput, deps)),
  );
  server.registerTool(
    "draft_job",
    {
      description:
        "임시저장 작업의 진행과 결과를 읽습니다. 돌고 있으면 끝나거나 wait_seconds 가 찰 때까지 기다립니다.",
      inputSchema: {
        job_id: z
          .string()
          .regex(JOB_ID_PATTERN)
          .describe("save_draft 나 overwrite_draft 가 돌려준 작업 번호"),
        wait_seconds: z
          .number()
          .int()
          .min(0)
          .max(50)
          .default(45)
          .describe("끝나기를 기다릴 최대 초. 0~50"),
      },
      annotations: { readOnlyHint: true },
    },
    ({ job_id, wait_seconds }) => guard(() => draftJob(env, job_id, wait_seconds)),
  );
  return server;
}

/** `--worker <작업 파일>` 인자. 없으면 `null`. */
function workerArgument(argv: string[]) {
  const index = argv.indexOf("--worker");
  return index >= 0 ? (argv[index + 1] ?? null) : null;
}

/**
 * 프록시가 있으면 같은 Bun 과 인자로 다시 시작한 뒤 표준 입출력을 그대로 잇는다.
 * `--worker <작업 파일>` 이면 MCP 서버를 띄우지 않고 임시저장 작업만 돌린다.
 */
export async function runServer() {
  if (hasProxyEnvironment(process.env)) {
    const child = Bun.spawn([process.execPath, ...process.argv.slice(1)], {
      cwd: process.cwd(),
      env: proxyFreeEnvironment(process.env),
      stdin: "inherit",
      stdout: "inherit",
      stderr: "inherit",
    });
    const forwardSigterm = () => child.kill("SIGTERM");
    const forwardSigint = () => child.kill("SIGINT");
    process.once("SIGTERM", forwardSigterm);
    process.once("SIGINT", forwardSigint);
    try {
      process.exitCode = await child.exited;
    } finally {
      process.off("SIGTERM", forwardSigterm);
      process.off("SIGINT", forwardSigint);
    }
    return;
  }
  const jobFile = workerArgument(process.argv);
  if (jobFile !== null) {
    await runWorker(jobFile);
    // 닫히지 않은 연결이 남아도 작업 프로세스는 여기서 끝난다.
    process.exit(0);
  }
  await createServer().connect(new StdioServerTransport());
}

if (import.meta.main) {
  if (!isSupportedBunVersion(Bun.version)) {
    process.stderr.write(
      `NAVER_BLOG_MCP_UNSUPPORTED_BUN_VERSION: expected Bun 1.3.14 or later, got ${Bun.version}\n`,
    );
    process.exitCode = 1;
  } else {
    try {
      await runServer();
    } catch {
      process.stderr.write("NAVER_BLOG_MCP_START_FAILED\n");
      process.exitCode = 2;
    }
  }
}
