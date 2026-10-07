import { createHash, randomUUID } from "node:crypto";
import { constants } from "node:fs";
import {
  chmod,
  lstat,
  mkdir,
  open,
  readdir,
  readFile,
  rename,
  stat,
  unlink,
} from "node:fs/promises";
import { join } from "node:path";
import { endpoint } from "./cdp.ts";
import { ToolError } from "./errors.ts";
import type { Env } from "./session.ts";

/**
 * 대시보드가 띄우는 MCP 서버에는 `TMPDIR` 이 없어 임시 디렉터리가 `/tmp` 다.
 * 승인한 호출과 Hermes 가 쥔 MCP 서버가 같은 파일 시스템에서 이 디렉터리를 본다.
 */
export const DEFAULT_JOB_DIR = "/tmp/fos-naver-blog-jobs";

export type JobStatus = "running" | "succeeded" | "failed" | "unknown";

/** 작업 상태 파일. 초안 본문과 사진 디렉터리, CDP 주소를 싣지 않는다. */
export type JobState = {
  job_id: string;
  status: JobStatus;
  stage: string;
  save_clicked: boolean;
  started_at: string;
  finished_at: string | null;
  result: unknown;
  error: Record<string, unknown> | null;
  pid: number | null;
  heartbeat_at: string | null;
};

/** 작업 프로세스가 `heartbeat_at` 을 갱신하는 간격. */
export const HEARTBEAT_MS = 5_000;
/** 이보다 오래 갱신이 없으면 작업 프로세스가 멈춘 것으로 본다. */
const HEARTBEAT_STALE_MS = 30_000;
/** 작업 프로세스 자신의 10분 제한과 겹치지 않게 1분 더 둔다. */
const JOB_STALE_MS = 11 * 60_000;
/** 작업 프로세스가 아직 `pid` 를 적지 않은 잠금을 살아 있다고 보는 시간. */
const STARTING_MS = 10_000;
/** 끝난 작업 파일을 남겨 두는 시간. */
const KEEP_MS = 24 * 60 * 60_000;

export const JOB_ID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const STATE_FILE = /^([0-9a-f-]{36})\.json$/;
const INPUT_FILE = /^([0-9a-f-]{36})\.input\.json$/;

const isFinished = (status: JobStatus) => status !== "running";
const unavailable = () => new ToolError("NAVER_BLOG_UNAVAILABLE");

export const stateFile = (dir: string, jobId: string) => join(dir, `${jobId}.json`);
export const inputFile = (dir: string, jobId: string) => join(dir, `${jobId}.input.json`);

/** CDP 주소마다 하나인 잠금 파일. 끝 `/` 만 다른 주소는 같은 브라우저다. */
export function lockFile(dir: string, cdpUrl: string) {
  const hash = createHash("sha256").update(endpoint(cdpUrl)).digest("hex");
  return join(dir, `lock-${hash.slice(0, 16)}`);
}

/**
 * 작업 디렉터리가 자기 uid 의 모드 700 디렉터리인지 본다. 없으면 만든다.
 * `/tmp` 는 다른 사용자도 쓰므로 남이 미리 만든 디렉터리나 링크를 쓰지 않는다.
 */
export async function checkJobDir(dir: string) {
  try {
    await mkdir(dir, { mode: 0o700 });
    // umask 가 모드를 줄였을 수 있어 다시 맞춘다.
    await chmod(dir, 0o700);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw unavailable();
  }
  let info;
  try {
    info = await lstat(dir);
  } catch {
    throw unavailable();
  }
  if (
    info.isSymbolicLink() ||
    !info.isDirectory() ||
    info.uid !== process.getuid?.() ||
    (info.mode & 0o777) !== 0o700
  )
    throw unavailable();
}

/** 작업 디렉터리를 확인하고 지울 파일을 지운 뒤 경로를 돌려준다. */
export async function openJobDir(env: Env) {
  const dir = env.NAVER_BLOG_JOB_DIR || DEFAULT_JOB_DIR;
  await checkJobDir(dir);
  await cleanupJobDir(dir);
  return dir;
}

/** 끝난 지 24시간이 지난 상태 파일과, 끝났거나 상태 파일이 없는 작업의 입력 파일을 지운다. */
export async function cleanupJobDir(dir: string, now = Date.now()) {
  const names = await readdir(dir).catch(() => [] as string[]);
  for (const name of names) {
    const stateMatch = STATE_FILE.exec(name);
    if (stateMatch) {
      const state = await readState(dir, stateMatch[1]!);
      if (
        state &&
        isFinished(state.status) &&
        now - Date.parse(state.finished_at ?? "") > KEEP_MS
      )
        await unlink(join(dir, name)).catch(() => {});
      continue;
    }
    const inputMatch = INPUT_FILE.exec(name);
    if (inputMatch) {
      const state = await readState(dir, inputMatch[1]!);
      if (!state || isFinished(state.status))
        await unlink(join(dir, name)).catch(() => {});
    }
  }
}

/** 상태 파일을 읽는다. 없거나 깨졌으면 `null`. */
export async function readState(dir: string, jobId: string): Promise<JobState | null> {
  try {
    return JSON.parse(await readFile(stateFile(dir, jobId), "utf8")) as JobState;
  } catch {
    return null;
  }
}

/** 모드 600 의 임시 파일에 쓴 뒤 이름을 바꿔 읽는 쪽이 반쯤 쓴 파일을 보지 않게 한다. */
async function writeAtomic(path: string, value: unknown) {
  const temporary = `${path}.${randomUUID()}.tmp`;
  const handle = await open(
    temporary,
    constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL,
    0o600,
  );
  try {
    await handle.writeFile(JSON.stringify(value));
  } finally {
    await handle.close();
  }
  try {
    await rename(temporary, path);
  } catch (error) {
    await unlink(temporary).catch(() => {});
    throw error;
  }
}

/** 새 작업의 상태 파일. 이미 있으면 실패한다. */
export async function createState(dir: string, state: JobState) {
  await writeAtomic(stateFile(dir, state.job_id), state);
}

/** 작업 프로세스가 읽을 초안 사본. 연결 칸 값은 담지 않는다. */
export async function writeInput(dir: string, jobId: string, input: unknown) {
  const handle = await open(
    inputFile(dir, jobId),
    constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL,
    0o600,
  );
  try {
    await handle.writeFile(JSON.stringify(input));
  } finally {
    await handle.close();
  }
}

/** 한 프로세스 안에서 같은 상태 파일을 고치는 일을 차례로 세운다. 갱신이 단계 기록을 덮지 않게 한다. */
const queues = new Map<string, Promise<unknown>>();

/**
 * 상태 파일을 다시 읽어 `patch` 를 합쳐 쓴다. 끝난 상태와 없는 파일에는 아무것도 쓰지 않는다.
 * 늦게 끝난 작업 프로세스와 묵은 작업을 정리하는 쪽이 서로의 결과를 덮지 않는다.
 * 쓴 뒤의 상태를, 쓰지 않았으면 읽은 상태를 돌려준다.
 */
export function updateState(
  dir: string,
  jobId: string,
  patch: Partial<JobState>,
): Promise<JobState | null> {
  const path = stateFile(dir, jobId);
  const work = (queues.get(path) ?? Promise.resolve())
    .catch(() => {})
    .then(async () => {
      const current = await readState(dir, jobId);
      if (!current || isFinished(current.status)) return current;
      const next = { ...current, ...patch };
      await writeAtomic(path, next);
      return next;
    });
  queues.set(path, work);
  void work.finally(() => {
    if (queues.get(path) === work) queues.delete(path);
  }).catch(() => {});
  return work;
}

/** 작업을 끝난 상태로 쓴다. 이미 끝났으면 그대로 둔다. */
export function finishState(
  dir: string,
  jobId: string,
  outcome: { status: Exclude<JobStatus, "running">; result?: unknown; error?: JobState["error"] },
) {
  return updateState(dir, jobId, {
    status: outcome.status,
    result: outcome.result ?? null,
    error: outcome.error ?? null,
    finished_at: new Date().toISOString(),
  });
}

/** `kill(pid, 0)` 으로 그 프로세스가 있는지 본다. 권한이 없다는 답도 있다는 뜻이다. */
function processExists(pid: number) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return (error as NodeJS.ErrnoException).code === "EPERM";
  }
}

/**
 * 작업이 아직 돌고 있는지 본다. 잠금을 만든 MCP 서버는 응답한 뒤 곧 닫히므로 작업 프로세스로 판정한다.
 * `pid` 가 있으면 그 프로세스가 있고 30초 안에 갱신했으며 시작한 지 11분이 안 됐어야 한다.
 * 상태 파일이나 `pid` 가 아직 없으면 `createdAt` 에서 10초 안일 때만 살아 있다.
 */
export function jobAlive(state: JobState | null, createdAt: number, now = Date.now()) {
  if (state && isFinished(state.status)) return false;
  if (!state || state.pid == null) return now - createdAt < STARTING_MS;
  return (
    processExists(state.pid) &&
    now - Date.parse(state.heartbeat_at ?? "") < HEARTBEAT_STALE_MS &&
    now - Date.parse(state.started_at) < JOB_STALE_MS
  );
}

/** 살아 있지 않은 작업을 끝낸다. 저장 단추를 누른 뒤였으면 `unknown`, 아니면 `timeout` 실패다. */
export function finishStale(dir: string, state: JobState) {
  return finishState(dir, state.job_id, {
    status: state.save_clicked ? "unknown" : "failed",
    error: { code: "timeout", stage: state.stage },
  });
}

type LockBody = { job_id?: unknown; created_at?: unknown };

async function readLock(path: string): Promise<{ jobId: string | null; createdAt: number } | null> {
  let raw: string;
  let modified: number;
  try {
    raw = await readFile(path, "utf8");
    modified = (await stat(path)).mtimeMs;
  } catch {
    return null;
  }
  try {
    const body = JSON.parse(raw) as LockBody;
    const createdAt = Date.parse(String(body.created_at));
    return {
      jobId: typeof body.job_id === "string" ? body.job_id : null,
      createdAt: Number.isFinite(createdAt) ? createdAt : modified,
    };
  } catch {
    return { jobId: null, createdAt: modified };
  }
}

async function createLock(path: string, jobId: string) {
  const handle = await open(
    path,
    constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL,
    0o600,
  );
  try {
    await handle.writeFile(
      JSON.stringify({ job_id: jobId, created_at: new Date().toISOString() }),
    );
  } finally {
    await handle.close();
  }
}

/**
 * 그 브라우저의 잠금을 만든다. 살아 있는 작업이 잡고 있으면 `NAVER_BLOG_BUSY` 다.
 * 묵은 잠금은 그 작업을 끝낸 뒤 지우고 한 번 더 만든다.
 */
export async function acquireLock(dir: string, cdpUrl: string, jobId: string) {
  const path = lockFile(dir, cdpUrl);
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      await createLock(path, jobId);
      return;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw unavailable();
    }
    const lock = await readLock(path);
    if (!lock) continue;
    const holder = lock.jobId && JOB_ID_PATTERN.test(lock.jobId)
      ? await readState(dir, lock.jobId)
      : null;
    if (jobAlive(holder, lock.createdAt)) throw new ToolError("NAVER_BLOG_BUSY");
    if (holder && !isFinished(holder.status)) await finishStale(dir, holder);
    await unlink(path).catch(() => {});
  }
  throw new ToolError("NAVER_BLOG_BUSY");
}

/** 그 작업이 잡은 잠금만 지운다. 다른 작업이 새로 잡은 잠금은 두고 간다. */
export async function releaseLock(dir: string, jobId: string) {
  const names = await readdir(dir).catch(() => [] as string[]);
  for (const name of names) {
    if (!name.startsWith("lock-")) continue;
    const path = join(dir, name);
    const lock = await readLock(path);
    if (lock?.jobId === jobId) await unlink(path).catch(() => {});
  }
}

/** 상태 파일에서 작업 프로세스 내부 칸을 뺀 결과. */
export function publicState(state: JobState) {
  const { pid: _pid, heartbeat_at: _heartbeat, ...rest } = state;
  return rest;
}
