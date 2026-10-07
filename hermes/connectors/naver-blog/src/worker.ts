import { readFile, unlink } from "node:fs/promises";
import { basename, dirname } from "node:path";
import { type DraftInput, parseBody } from "./draft.ts";
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
  /** 작업 하나의 시간 상한. 지나면 `runDraft` 를 멈추고 `timeout` 으로 끝낸다. */
  limitMs: number;
  /** 연결 칸 값을 읽는 env. 작업 프로세스는 자기 env 를 쓴다. */
  env: Env;
};

const DEFAULT_DEPS: WorkerDeps = { runDraft, limitMs: 600_000, env: process.env };

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

/** 입력 파일을 읽자마자 지운다. 읽지 못해도 지운다. */
async function takeInput(dir: string, jobId: string) {
  const path = inputFile(dir, jobId);
  try {
    return draftFrom(JSON.parse(await readFile(path, "utf8")));
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
 * 분리된 작업 프로세스의 본체. `jobFile` 은 `<작업 디렉터리>/<job_id>.json` 이다.
 * 자기 `pid` 를 적고 5초마다 `heartbeat_at` 을 갱신하며, 입력 파일을 읽고 지운 뒤 `runDraft` 를 돌린다.
 * 저장 단추를 누르기 직전에 `save_clicked` 를 쓰고 다시 읽어, 그 사이 정리가 작업을 끝냈으면 누르지 않는다.
 * 끝나면 결과를 쓰고 자기 잠금을 푼다. 끝난 상태는 다시 쓰지 않는다.
 * 작업 디렉터리 확인이나 상태 쓰기처럼 `runDraft` 밖에서 난 예외도 `editor_failed` 로 끝내고 잠금을 푼다.
 */
export async function runWorker(jobFile: string, overrides: Partial<WorkerDeps> = {}) {
  const deps = { ...DEFAULT_DEPS, ...overrides };
  const dir = dirname(jobFile);
  const jobId = basename(jobFile).replace(/\.json$/, "");
  if (!JOB_ID_PATTERN.test(jobId) || basename(jobFile) !== `${jobId}.json`) return;
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
    await checkJobDir(dir);
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
      const input = await takeInput(dir, jobId);
      if (!input) {
        outcome = { status: "failed", error: { code: "editor_failed", stage } };
      } else {
        const onStage = async (next: RunStage) => {
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
          const { state, savedBefore, savedAfter } = await deps.runDraft(
            deps.env,
            parseBody(input.body),
            input,
            onStage,
            controller.signal,
          );
          outcome = {
            status: "succeeded",
            result: { state, saved_before: savedBefore, saved_after: savedAfter },
          };
        } catch (error) {
          if (clicked) {
            outcome = {
              status: "unknown",
              error: controller.signal.aborted && !(error instanceof EditorError)
                ? { code: "timeout", stage }
                : errorOf(error, stage),
            };
          } else if (controller.signal.aborted) {
            outcome = { status: "failed", error: { code: "timeout", stage } };
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

/**
 * 시험이 실제 작업 프로세스를 띄울 때 쓰는 대역. 브라우저에 닿지 않고 잠깐 기다린 뒤 성공한다.
 * 기다리는 동안 시험이 작업 프로세스의 프로세스 묶음을 확인한다.
 */
export const fakeRunDraft: typeof runDraft = async (_env, _blocks, input, onStage) => {
  await onStage("open");
  await Bun.sleep(800);
  return {
    state: {
      title: input.title,
      photos: 0,
      fitted_photos: 0,
      stickers: 0,
      maps: 0,
      category: input.category,
      tags: input.tags,
      saved_count: 1,
    },
    savedBefore: 0,
    savedAfter: 1,
  };
};
