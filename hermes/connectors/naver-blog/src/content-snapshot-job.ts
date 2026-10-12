import { rm } from "node:fs/promises";
import { z } from "zod";
import { compiledSnapshotV1Schema } from "./content-draft.ts";
import {
  AUTHORIZE_URL_ENV,
  authorizeUrl,
  TICKET_ENV,
} from "./content-snapshot-guard.ts";
import { ToolError } from "./errors.ts";
import {
  acquireLock,
  createState,
  finishStale,
  finishState,
  inputFile,
  JOB_ID_PATTERN,
  jobAlive,
  openJobDir,
  readState,
  releaseLock,
  stateFile,
  type JobState,
  updateState,
  writeInput,
} from "./jobs.ts";
import { spawnWorker, workerEnvironment } from "./worker-spawn.ts";
import { readConnection, sessionStatus, type Env } from "./session.ts";

const hex64 = z.string().regex(/^[0-9a-f]{64}$/);
const decimal = z
  .string()
  .regex(/^[1-9][0-9]{0,18}$/)
  .refine((value) => BigInt(value) <= 9223372036854775807n);
// Control Plane 이 DB 와 같은 MICRO 정밀도로 절삭한 UTC 시각이다.
const micros = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/);
const uuid = z.string().regex(JOB_ID_PATTERN);

export const snapshotStampSchema = z.strictObject({
  protocol: z.literal("content-snapshot-v1"),
  snapshotId: uuid,
  jobId: uuid,
  snapshotHash: hex64,
  bindingId: decimal,
  connectionId: decimal,
  bindingUpdatedAt: micros,
  connectionUpdatedAt: micros,
  profileHash: hex64,
});

export type SnapshotStamp = z.infer<typeof snapshotStampSchema>;
/**
 * CP 가 예약한 원고 job 의 상태 파일. 기존 JobState 에 stamp 키 하나만 더하고 기존 DSL job 은 그대로 읽는다.
 * 이 키가 있으면 값이 깨졌어도 private 조회만 읽는다.
 */
export type SnapshotJobState = JobState & { snapshot_stamp?: unknown };

export const startInputSchema = z.strictObject({
  snapshot: compiledSnapshotV1Schema,
  jobId: uuid,
  // env 로 넘기므로 공백·줄바꿈 없는 ASCII 만 받는다.
  ticket: z.string().regex(/^[\x21-\x7e]{1,4096}$/),
  stamp: snapshotStampSchema,
});
export const statusInputSchema = z.strictObject({ stamp: snapshotStampSchema });

export type SnapshotJobDeps = {
  workerEntry: string;
  startWaitMs: number;
  checkSession: typeof sessionStatus;
};

export const DEFAULT_SNAPSHOT_JOB_DEPS: SnapshotJobDeps = {
  workerEntry: process.argv[1] ?? "",
  startWaitMs: 5_000,
  checkSession: sessionStatus,
};

/** 결과를 내지 않는 고정 거절. 본문·경로·stamp 를 싣지 않는다. */
export class SnapshotJobError extends Error {
  constructor(
    readonly code:
      "invalid_input" | "not_found" | "start_unknown" | "unavailable",
  ) {
    super(code);
  }
}

const STAGES = new Set([
  "queued",
  "open",
  "fill",
  "photos",
  "components",
  "settings",
  "save",
  "state",
]);
const STATUSES = new Set(["running", "succeeded", "failed", "unknown"]);

/** 상태 파일의 stamp 를 strict decode 해 요청 stamp 의 모든 칸과 대조한다. */
export function sameStamp(state: SnapshotJobState, stamp: SnapshotStamp) {
  if (!Object.hasOwn(state, "snapshot_stamp")) return false;
  const stored = snapshotStampSchema.safeParse(state.snapshot_stamp);
  return (
    stored.success &&
    (Object.keys(snapshotStampSchema.shape) as (keyof SnapshotStamp)[]).every(
      (key) => stored.data[key] === stamp[key],
    )
  );
}

/** 같은 예약 ID 의 상태가 있으면 stamp 를 대조해 돌려준다. 다른 job 이면 거절하고, 없으면 `null` 이다. */
async function reserved(dir: string, stamp: SnapshotStamp) {
  const state = await readState(dir, stamp.jobId);
  if (state && !sameStamp(state, stamp))
    throw new SnapshotJobError("not_found");
  return state;
}

const accepted = (state: JobState, stamp: SnapshotStamp) => ({
  job_id: state.job_id,
  status: state.status,
  stamp,
});

/** 시작 전 거절의 안전한 code. 세션 확인과 잠금의 도구 오류만 이름을 옮긴다. */
function refusalCode(error: unknown) {
  return error instanceof ToolError
    ? error.code.replace(/^NAVER_BLOG_/, "").toLowerCase()
    : "start_failed";
}

/**
 * CP 가 예약한 jobId 로 기존 worker 를 한 번 띄운다. 같은 ID 상태가 이미 있으면 stamp 를 대조해 그 상태만 돌려준다.
 * 상태 파일을 `O_EXCL` 로 먼저 만들어 예약 ID 를 고정하고, 그 뒤의 실패는 지우지 않고 failed 로 끝낸다.
 * ticket 은 입력 파일과 상태 파일에 쓰지 않고 이 worker 의 env 로만 넘긴다.
 */
export async function startSnapshotJob(
  env: Env,
  raw: unknown,
  deps: SnapshotJobDeps,
) {
  const parsed = startInputSchema.safeParse(raw);
  const url = authorizeUrl(env[AUTHORIZE_URL_ENV]);
  if (
    !parsed.success ||
    parsed.data.stamp.jobId !== parsed.data.jobId ||
    parsed.data.stamp.snapshotHash !== parsed.data.snapshot.snapshotHash
  )
    throw new SnapshotJobError("invalid_input");
  if (!url) throw new SnapshotJobError("unavailable");
  const { snapshot, jobId, ticket, stamp } = parsed.data;
  const dir = await openJobDir(env);
  const existing = await reserved(dir, stamp);
  if (existing) return accepted(existing, stamp);
  try {
    const reservation: SnapshotJobState = {
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
      snapshot_stamp: stamp,
    };
    await createState(dir, reservation);
  } catch {
    // 동시에 같은 ID 로 들어온 요청이 먼저 만들었으면 그 상태를 돌려준다.
    const raced = await reserved(dir, stamp);
    if (raced) return accepted(raced, stamp);
    throw new SnapshotJobError("unavailable");
  }
  const refuse = async (code: string) => {
    await finishState(dir, jobId, {
      status: "failed",
      error: { code, stage: "queued" },
    });
    await rm(inputFile(dir, jobId), { force: true });
    await releaseLock(dir, jobId);
    const state = await readState(dir, jobId);
    if (!state) throw new SnapshotJobError("unavailable");
    return accepted(state, stamp);
  };
  try {
    const { blogId } = readConnection(env);
    await deps.checkSession(env, { timeoutMs: 45_000 });
    await acquireLock(dir, blogId, jobId);
  } catch (error) {
    return refuse(refusalCode(error));
  }
  // 세션 확인이 길었어도 pid 없는 시작 대기 10초를 지금부터 센다. 그 사이 조회가 끝냈으면 띄우지 않는다.
  const fresh = await updateState(dir, jobId, {
    started_at: new Date().toISOString(),
  });
  if (!fresh || fresh.status !== "running") {
    await releaseLock(dir, jobId);
    if (!fresh) throw new SnapshotJobError("unavailable");
    return accepted(fresh, stamp);
  }
  try {
    await writeInput(dir, jobId, { kind: "content_snapshot", snapshot });
    spawnWorker(deps.workerEntry, stateFile(dir, jobId), {
      ...workerEnvironment(env),
      [TICKET_ENV]: ticket,
      [AUTHORIZE_URL_ENV]: url,
    });
  } catch {
    return refuse("start_failed");
  }
  const deadline = Date.now() + deps.startWaitMs;
  for (;;) {
    const state = await readState(dir, jobId);
    // worker 가 pid 를 적으면 접수다. 그 뒤 CP authorize(start) 는 worker 가 부른다.
    if (state && (state.pid != null || state.status !== "running"))
      return accepted(state, stamp);
    if (Date.now() >= deadline) throw new SnapshotJobError("start_unknown");
    await Bun.sleep(Math.min(100, Math.max(1, deadline - Date.now())));
  }
}

/** 허용한 칸만 옮긴다. 원문 결과·오류 메시지·pid·heartbeat 는 싣지 않는다. */
export function projectStatus(state: JobState, stamp: SnapshotStamp) {
  const result = (state.result ?? {}) as Record<string, unknown>;
  const count = (value: unknown) =>
    state.status === "succeeded" &&
    Number.isSafeInteger(value) &&
    (value as number) >= 0
      ? (value as number)
      : null;
  const code = state.error?.code;
  return {
    job_id: state.job_id,
    stamp,
    status: STATUSES.has(state.status) ? state.status : "unknown",
    stage: STAGES.has(state.stage) ? state.stage : null,
    save_clicked: state.save_clicked === true,
    finished_at:
      typeof state.finished_at === "string" ? state.finished_at : null,
    saved_before: count(result.saved_before),
    saved_after: count(result.saved_after),
    error_code:
      typeof code === "string" && /^[a-z][a-z_]{0,63}$/.test(code)
        ? code
        : null,
  };
}

/**
 * CP 가 보관한 stamp 로 같은 job 만 조회한다. stamp 가 하나라도 다르거나 상태가 없으면 결과 없이 거절한다.
 * 돌고 있다는 job 이 멈췄으면 공개 조회와 같은 규칙으로 묵은 작업으로 끝낸다. worker 를 새로 띄우지 않는다.
 */
export async function snapshotJobStatus(env: Env, raw: unknown) {
  const parsed = statusInputSchema.safeParse(raw);
  if (!parsed.success) throw new SnapshotJobError("invalid_input");
  const { stamp } = parsed.data;
  const dir = await openJobDir(env);
  let state = await readState(dir, stamp.jobId);
  if (!state || !sameStamp(state, stamp))
    throw new SnapshotJobError("not_found");
  if (
    state.status === "running" &&
    !jobAlive(state, Date.parse(state.started_at))
  ) {
    state = (await finishStale(dir, state)) ?? state;
    await releaseLock(dir, stamp.jobId);
  }
  return projectStatus(state, stamp);
}
