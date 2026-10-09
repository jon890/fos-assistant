import { readFile, unlink } from "node:fs/promises";
import { basename, dirname } from "node:path";
import { type DraftInput, parseBody } from "./draft.ts";
import { type OverwriteInput, type OverwriteStage, runOverwrite } from "./editor/overwrite.ts";
import { EditorError, runDraft, type RunStage } from "./editor/run.ts";
import {
  checkJobDir,
  finishClaimed,
  finishState,
  HEARTBEAT_MS,
  inputFile,
  JOB_ID_PATTERN,
  readState,
  releaseLock,
  updateState,
} from "./jobs.ts";
import type { Env } from "./session.ts";

export type WorkerDeps = {
  runDraft: typeof runDraft;
  runOverwrite: typeof runOverwrite;
  /** 작업 하나의 시간 상한. 지나면 `runDraft` 나 `runOverwrite` 를 멈추고 `timeout` 으로 끝낸다. */
  limitMs: number;
  /** 연결 칸 값을 읽는 env. 작업 프로세스는 자기 env 를 쓴다. */
  env: Env;
};

const DEFAULT_DEPS: WorkerDeps = { runDraft, runOverwrite, limitMs: 600_000, env: process.env };

/** 입력 파일이 담은 작업. */
type JobInput = { kind: "save"; draft: DraftInput } | { kind: "overwrite"; input: OverwriteInput };

/** 입력 파일의 다섯 칸만 꺼낸다. 모양이 틀리면 `null`. */
function draftFrom(raw: unknown): DraftInput | null {
  const value = raw as Partial<DraftInput> | null;
  if (
    !value ||
    typeof value.title !== "string" ||
    typeof value.category !== "string" ||
    !Array.isArray(value.tags) ||
    !value.tags.every((tag) => typeof tag === "string") ||
    typeof value.body !== "string" ||
    (value.photo_dir !== undefined && typeof value.photo_dir !== "string")
  )
    return null;
  return {
    title: value.title,
    category: value.category,
    tags: value.tags,
    body: value.body,
    ...(value.photo_dir === undefined ? {} : { photo_dir: value.photo_dir }),
  };
}

/** 덮어쓰기 입력 파일의 일곱 칸만 꺼낸다. 모양이 틀리면 `null`. */
function overwriteFrom(value: Record<string, unknown>): OverwriteInput | null {
  const { draft_id, revision, changes, title, category, tags, body } = value;
  if (
    typeof draft_id !== "string" ||
    typeof revision !== "string" ||
    typeof changes !== "string" ||
    typeof title !== "string" ||
    typeof category !== "string" ||
    !Array.isArray(tags) ||
    !tags.every((tag) => typeof tag === "string") ||
    typeof body !== "string"
  )
    return null;
  return { draft_id, revision, changes, title, category, tags, body };
}

/**
 * 입력 파일을 작업으로 읽는다. 모양이 틀리면 `null`.
 * `kind` 가 없으면 새 글 저장이다. 배포하는 동안 옛 MCP 서버가 쓴 입력 파일도 그대로 읽는다.
 */
function jobFrom(raw: unknown): JobInput | null {
  if (!raw || typeof raw !== "object") return null;
  const value = raw as Record<string, unknown>;
  if (value.kind === "overwrite") {
    const input = overwriteFrom(value);
    return input ? { kind: "overwrite", input } : null;
  }
  if (value.kind !== undefined) return null;
  const draft = draftFrom(value);
  return draft ? { kind: "save", draft } : null;
}

/** 입력 파일을 읽자마자 지운다. 읽지 못해도 지운다. */
async function takeInput(dir: string, jobId: string) {
  const path = inputFile(dir, jobId);
  try {
    return jobFrom(JSON.parse(await readFile(path, "utf8")));
  } catch {
    return null;
  } finally {
    await unlink(path).catch(() => {});
  }
}

/** `EditorError` 를 작업 상태의 `error` 로 옮긴다. 그 밖의 예외는 원문을 싣지 않는다. */
function errorOf(error: unknown, stage: string): Record<string, unknown> {
  if (error instanceof EditorError)
    return { code: error.code, stage: error.stage, message: error.message, ...error.extra };
  return { code: "editor_failed", stage };
}

/**
 * 시간 상한으로 끝난 작업의 `error`. 잡은 예외가 사본 번호를 실은 `EditorError` 면 그 칸 하나만 더한다.
 * 사용자가 남은 원본 사본을 찾을 수 있게 하려는 것이고, 다른 `extra` 칸은 싣지 않는다.
 */
function timeoutOf(error: unknown, stage: string): Record<string, unknown> {
  const backup = error instanceof EditorError ? error.extra.backup_draft_id : undefined;
  return { code: "timeout", stage, ...(backup === undefined ? {} : { backup_draft_id: backup }) };
}

/**
 * 분리된 작업 프로세스의 본체. `jobFile` 은 `<작업 디렉터리>/<job_id>.json` 이다.
 * 자기 `pid` 를 적고 5초마다 `heartbeat_at` 을 갱신하며, 입력 파일을 읽고 지운 뒤
 * 새 글이면 `runDraft` 를, 덮어쓰기면 `runOverwrite` 를 돌린다.
 * 저장 단추를 누르기 직전에 `save_clicked` 를 쓰고 다시 읽어, 그 사이 정리가 작업을 끝냈으면 누르지 않는다.
 * 끝나면 결과를 쓰고 자기 잠금을 푼다. 끝난 상태는 다시 쓰지 않는다.
 * 상태 쓰기처럼 편집기 흐름 밖에서 난 예외도 `editor_failed` 로 끝내고 잠금을 푼다.
 * 작업 디렉터리 확인이 실패하면 그 디렉터리를 믿을 수 없으므로 아무것도 쓰거나 지우지 않고 끝낸다.
 */
export async function runWorker(jobFile: string, overrides: Partial<WorkerDeps> = {}) {
  const deps = { ...DEFAULT_DEPS, ...overrides };
  const dir = dirname(jobFile);
  const jobId = basename(jobFile).replace(/\.json$/, "");
  if (!JOB_ID_PATTERN.test(jobId) || basename(jobFile) !== `${jobId}.json`) return;
  try {
    await checkJobDir(dir);
  } catch {
    // 남이 바꿨을 수 있는 디렉터리에 상태를 쓰거나 입력과 잠금을 지우지 않는다. 디렉터리가 바로잡히면
    // 다음 호출의 묵은 작업 정리가 pid 없는 이 작업을 timeout 으로 끝내고 입력과 잠금을 지운다.
    return;
  }
  let stage = "queued";
  let clicked = false;
  try {
    await work();
  } catch {
    await finishState(dir, jobId, {
      status: clicked ? "unknown" : "failed",
      error: { code: "editor_failed", stage },
    }).catch(() => {});
    await unlink(inputFile(dir, jobId)).catch(() => {});
    await releaseLock(dir, jobId);
  }

  async function work() {
    const heartbeat = () => updateState(dir, jobId, { heartbeat_at: new Date().toISOString() });
    const started = await updateState(dir, jobId, {
      pid: process.pid,
      heartbeat_at: new Date().toISOString(),
    });
    if (!started || started.status !== "running") {
      await unlink(inputFile(dir, jobId)).catch(() => {});
      return;
    }
    const timer = setInterval(() => void heartbeat().catch(() => {}), HEARTBEAT_MS);
    const controller = new AbortController();
    const limit = setTimeout(() => controller.abort(), deps.limitMs);
    stage = started.stage;
    let outcome: Parameters<typeof finishState>[2];
    try {
      const job = await takeInput(dir, jobId);
      if (!job) {
        outcome = { status: "failed", error: { code: "editor_failed", stage } };
      } else {
        const onStage = async (next: RunStage | OverwriteStage) => {
          if (next === "save_clicking") {
            const written = await updateState(dir, jobId, { save_clicked: true, stage: "save" });
            // 쓴 뒤 다시 읽는다. 정리가 이미 이 작업을 끝냈거나 끝내기로 했으면 저장 단추를 누르지 않는다.
            const current = await readState(dir, jobId);
            if (
              written?.save_clicked !== true ||
              current?.status !== "running" ||
              current.save_clicked !== true ||
              (await finishClaimed(dir, jobId))
            )
              throw new Error("job already finished");
            clicked = true;
            stage = "save";
            return;
          }
          stage = next;
          await updateState(dir, jobId, { stage: next });
        };
        try {
          if (job.kind === "overwrite") {
            const result = await deps.runOverwrite(deps.env, job.input, onStage, controller.signal);
            outcome = { status: "succeeded", result };
          } else {
            const { state, savedBefore, savedAfter } = await deps.runDraft(
              deps.env,
              parseBody(job.draft.body),
              job.draft,
              onStage,
              controller.signal,
            );
            outcome = {
              status: "succeeded",
              result: { state, saved_before: savedBefore, saved_after: savedAfter },
            };
          }
        } catch (error) {
          if (clicked) {
            outcome = {
              status: "unknown",
              error: controller.signal.aborted && !(error instanceof EditorError)
                ? timeoutOf(error, stage)
                : errorOf(error, stage),
            };
          } else if (controller.signal.aborted) {
            outcome = { status: "failed", error: timeoutOf(error, stage) };
          } else {
            outcome = { status: "failed", error: errorOf(error, stage) };
          }
        }
      }
    } finally {
      clearTimeout(limit);
      clearInterval(timer);
    }
    try {
      await finishState(dir, jobId, outcome);
    } finally {
      await releaseLock(dir, jobId);
    }
  }
}
