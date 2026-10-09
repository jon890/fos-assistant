import { createHash, randomUUID } from "node:crypto";
import { constants } from "node:fs";
import {
  chmod,
  link,
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
/** 끝냄 표시를 만든 쪽이 이 시간 안에 끝난 상태를 쓰지 않았으면 그 쪽이 쓰다 죽은 것으로 본다. */
const CLAIM_STALE_MS = 10_000;
/** 끝난 작업 파일을 남겨 두는 시간. */
const KEEP_MS = 24 * 60 * 60_000;

export const JOB_ID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const STATE_FILE = /^([0-9a-f-]{36})\.json$/;
const INPUT_FILE = /^([0-9a-f-]{36})\.input\.json$/;
const FINISHED_FILE = /^([0-9a-f-]{36})\.finished$/;
/**
 * 쓰다 남은 임시 파일과 되돌리지 못한 묵은 잠금. 만든 프로세스가 지우기 전에 죽었으면 남는다.
 * `<상태 파일>.<uuid>.tmp`, `lock-<hash>.<uuid>.stale`, `lock-<hash>.stale-<job_id>` 다.
 */
const ORPHAN_FILE =
  /^(?:[0-9a-f-]{36}\.json\.[0-9a-f-]{36}\.tmp|lock-[0-9a-f]{16}\.(?:[0-9a-f-]{36}\.stale|stale-[0-9a-f-]{36}))$/;

const isFinished = (status: JobStatus) => status !== "running";
const unavailable = () => new ToolError("NAVER_BLOG_UNAVAILABLE");

export const stateFile = (dir: string, jobId: string) => join(dir, `${jobId}.json`);
export const inputFile = (dir: string, jobId: string) => join(dir, `${jobId}.input.json`);
/**
 * 작업을 끝낼 권리 표시. 작업 프로세스와 묵은 작업을 정리하는 쪽은 서로 다른 프로세스라
 * 이 파일을 먼저 만든 쪽만 끝난 상태를 쓰고, 작업 프로세스는 이 파일이 있으면 더 쓰지 않는다.
 */
export const finishedFile = (dir: string, jobId: string) => join(dir, `${jobId}.finished`);

/** 블로그마다 하나인 잠금 파일. 중계 주소는 바인딩마다 달라 같은 브라우저도 여러 주소로 온다. */
export function lockFile(dir: string, blogId: string) {
  // 블로그 주소는 아이디의 대소문자를 가리지 않으므로 소문자로 맞춘 뒤 해시한다.
  const hash = createHash("sha256").update(blogId.toLowerCase()).digest("hex");
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

/**
 * 끝난 지 24시간이 지난 상태 파일, 만든 지 24시간이 지난 끝냄 표시와 고아 임시 파일, 묵은 잠금,
 * 끝났거나 상태 파일이 없는 작업의 입력 파일을 지운다.
 */
export async function cleanupJobDir(dir: string, now = Date.now()) {
  const names = await readdir(dir).catch(() => [] as string[]);
  for (const name of names) {
    if (FINISHED_FILE.test(name) || ORPHAN_FILE.test(name)) {
      const path = join(dir, name);
      const info = await stat(path).catch(() => null);
      if (info && now - info.mtimeMs > KEEP_MS) await unlink(path).catch(() => {});
      continue;
    }
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

/**
 * 새 작업의 상태 파일. 이미 있으면 `EEXIST` 로 실패한다.
 * 다 쓴 임시 파일을 `link` 로 붙여 읽는 쪽이 반쯤 쓴 파일을 보지 않고, 있는 파일을 덮지도 않는다.
 */
export async function createState(dir: string, state: JobState) {
  const path = stateFile(dir, state.job_id);
  const temporary = `${path}.${randomUUID()}.tmp`;
  const handle = await open(
    temporary,
    constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL,
    0o600,
  );
  try {
    await handle.writeFile(JSON.stringify(state));
  } finally {
    await handle.close();
  }
  try {
    await link(temporary, path);
  } finally {
    await unlink(temporary).catch(() => {});
  }
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

function queued<T>(path: string, task: () => Promise<T>): Promise<T> {
  const work = (queues.get(path) ?? Promise.resolve()).catch(() => {}).then(task);
  queues.set(path, work);
  void work.finally(() => {
    if (queues.get(path) === work) queues.delete(path);
  }).catch(() => {});
  return work;
}

async function exists(path: string) {
  try {
    await lstat(path);
    return true;
  } catch {
    return false;
  }
}

/** 끝냄 표시가 있으면 참. 작업 프로세스는 쓰기 전에 이것을 보고, 있으면 쓰지 않는다. */
export function finishClaimed(dir: string, jobId: string) {
  return exists(finishedFile(dir, jobId));
}

/**
 * 상태 파일을 다시 읽어 `patch` 를 합쳐 쓴다. 끝난 상태와 없는 파일, 끝냄 표시가 있는 작업에는 아무것도 쓰지 않는다.
 * 다른 프로세스가 작업을 끝내기로 한 뒤에 heartbeat 나 단계 기록이 `running` 을 되살리지 않는다.
 * 쓴 뒤의 상태를, 쓰지 않았으면 읽은 상태를 돌려준다.
 */
export function updateState(
  dir: string,
  jobId: string,
  patch: Partial<JobState>,
): Promise<JobState | null> {
  const path = stateFile(dir, jobId);
  return queued(path, async () => {
    const current = await readState(dir, jobId);
    if (!current || isFinished(current.status) || (await finishClaimed(dir, jobId)))
      return current;
    const next = { ...current, ...patch };
    // 확인과 쓰기 사이는 프로세스 사이에서 잠기지 않는다. 그 틈에 정리가 끝냄 표시를 만들고 끝난 상태를 쓰면
    // 이 쓰기가 `running` 으로 덮을 수 있다. `finishState` 가 쓴 뒤 다시 읽어 한 번 더 쓰므로 대개 바로잡히고,
    // 그 뒤에도 덮였으면 작업 프로세스는 끝냄 표시를 보고 더 쓰지 않아 heartbeat 가 멈춘다.
    // 그러면 다음 정리가 묵은 작업으로 보고 10초 지난 끝냄 표시를 넘겨받아 다시 끝낸다.
    await writeAtomic(path, next);
    return next;
  });
}

/** 만든 지 10초가 지난 끝냄 표시면 참. 표시를 만든 쪽이 상태를 쓰기 전에 죽었다는 뜻이다. */
async function abandonedClaim(dir: string, jobId: string) {
  const info = await stat(finishedFile(dir, jobId)).catch(() => null);
  return info !== null && Date.now() - info.mtimeMs > CLAIM_STALE_MS;
}

/** 끝냄 표시를 `O_EXCL` 로 만든다. 만든 쪽만 참이다. */
async function claimFinish(dir: string, jobId: string) {
  try {
    const handle = await open(
      finishedFile(dir, jobId),
      constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL,
      0o600,
    );
    await handle.close();
    return true;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "EEXIST") return false;
    throw error;
  }
}

type Outcome = {
  status: Exclude<JobStatus, "running">;
  result?: unknown;
  error?: JobState["error"];
};

/**
 * 작업을 끝난 상태로 쓴다. 끝냄 표시를 먼저 만든 쪽만 쓰고, 이미 끝났거나 남이 표시를 만들었으면 그대로 둔다.
 * 남이 만든 표시가 10초가 지나도 상태가 `running` 이면 그 쪽이 쓰다 죽은 것으로 보고 대신 쓴다.
 * `outcome` 이 함수면 표시를 만든 뒤 읽은 상태로 결과를 정한다.
 * 쓴 뒤 다시 읽어, 표시를 만들기 직전에 읽고 늦게 쓴 갱신이 `running` 으로 덮었으면 한 번 더 쓴다.
 */
export function finishState(
  dir: string,
  jobId: string,
  outcome: Outcome | ((current: JobState) => Outcome),
) {
  const path = stateFile(dir, jobId);
  return queued(path, async () => {
    const before = await readState(dir, jobId);
    if (!before || isFinished(before.status)) return before;
    if (!(await claimFinish(dir, jobId)) && !(await abandonedClaim(dir, jobId)))
      return readState(dir, jobId);
    const current = (await readState(dir, jobId)) ?? before;
    if (isFinished(current.status)) return current;
    const decided = typeof outcome === "function" ? outcome(current) : outcome;
    const next: JobState = {
      ...current,
      status: decided.status,
      result: decided.result ?? null,
      error: decided.error ?? null,
      finished_at: new Date().toISOString(),
    };
    await writeAtomic(path, next);
    const after = await readState(dir, jobId);
    if (after && !isFinished(after.status)) await writeAtomic(path, next);
    return next;
  });
}

/**
 * 작업이 아직 돌고 있는지 본다. 잠금을 만든 MCP 서버는 응답한 뒤 곧 닫히므로 작업 프로세스가 남긴 상태로 판정한다.
 * `pid` 가 있으면 30초 안에 `heartbeat_at` 을 갱신했고 시작한 지 11분이 안 됐어야 한다.
 * `kill(pid, 0)` 은 보지 않는다. 승인한 호출과 Hermes 가 쥔 MCP 서버의 PID 네임스페이스가 다르면
 * 살아 있는 작업 프로세스도 없다고 답하기 때문이다. 그 대신 작업 프로세스가 죽었다는 판정이 최대 30초 늦어진다.
 * 상태 파일이나 `pid` 가 아직 없으면 `createdAt` 에서 10초 안일 때만 살아 있다.
 */
export function jobAlive(state: JobState | null, createdAt: number, now = Date.now()) {
  if (state && isFinished(state.status)) return false;
  if (!state || state.pid == null) return now - createdAt < STARTING_MS;
  return (
    now - Date.parse(state.heartbeat_at ?? "") < HEARTBEAT_STALE_MS &&
    now - Date.parse(state.started_at) < JOB_STALE_MS
  );
}

/**
 * 살아 있지 않은 작업을 끝낸다. 저장 단추를 누른 뒤였으면 `unknown`, 아니면 `timeout` 실패다.
 * 판정은 끝냄 표시를 만든 뒤 다시 읽은 상태로 한다. 그 사이 작업 프로세스가 `save_clicked` 를 썼을 수 있다.
 */
export function finishStale(dir: string, state: JobState) {
  return finishState(dir, state.job_id, (current) => ({
    status: current.save_clicked ? "unknown" : "failed",
    error: { code: "timeout", stage: current.stage },
  }));
}

type LockBody = { job_id?: unknown; created_at?: unknown };

export async function readLock(path: string): Promise<{ jobId: string | null; createdAt: number } | null> {
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
 * 그 블로그의 잠금을 만든다. 살아 있는 작업이 잡고 있으면 `NAVER_BLOG_BUSY` 다.
 * 묵은 잠금은 그 작업을 끝낸 뒤 지우고 한 번 더 만든다.
 */
export async function acquireLock(dir: string, blogId: string, jobId: string) {
  const path = lockFile(dir, blogId);
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
    await removeStaleLock(path, lock.jobId);
  }
  throw new ToolError("NAVER_BLOG_BUSY");
}

/**
 * 묵었다고 판정한 잠금을 지운다. 판정한 뒤 다른 호출이 그 잠금을 지우고 새로 만들었을 수 있어,
 * 임시 이름으로 옮긴 뒤 내용이 판정한 `jobId` 와 같을 때만 지운다. 다르면 제자리로 되돌린다.
 *
 * 옮기기와 되돌리기 사이에는 그 자리에 잠금이 없다. 그 틈에 또 다른 호출이 새 잠금을 만들면 되돌리는 `link` 가
 * 실패하고, 옮긴 잠금의 작업과 새 잠금의 작업이 같은 브라우저를 함께 쓸 수 있다. 이 틈은 막지 않는다.
 * 그때 옮긴 잠금은 살아 있는 작업의 것일 수 있어 지우지 않고 `lock-<hash>.stale-<job_id>` 로 남긴다.
 * `lock-` 으로 시작하므로 그 작업의 `releaseLock` 이 내용으로 찾아 지우고, 못 지운 것은 `cleanupJobDir` 가 지운다.
 */
export async function removeStaleLock(path: string, jobId: string | null) {
  const moved = `${path}.${randomUUID()}.stale`;
  try {
    await rename(path, moved);
  } catch {
    return;
  }
  const lock = await readLock(moved);
  if (!lock || lock.jobId === jobId) {
    await unlink(moved).catch(() => {});
    return;
  }
  try {
    await link(moved, path);
  } catch {
    if (lock.jobId && JOB_ID_PATTERN.test(lock.jobId))
      await rename(moved, `${path}.stale-${lock.jobId}`).catch(() => {});
    return;
  }
  await unlink(moved).catch(() => {});
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
