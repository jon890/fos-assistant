import { afterEach, expect, test } from "bun:test";
import { randomUUID } from "node:crypto";
import { chmod, mkdtemp, readdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { EditorError, type runDraft } from "../src/editor/run.ts";
import {
  acquireLock,
  createState,
  finishState,
  readState,
  stateFile,
  writeInput,
} from "../src/jobs.ts";
import { runWorker } from "../src/worker.ts";

const CDP_URL = "http://127.0.0.1:9";
const ENV = { NAVER_BLOG_CDP_URL: CDP_URL, NAVER_BLOG_ID: "example-blog" };
const INPUT = {
  title: "가상국수 다녀온 날",
  category: "가상국수로그",
  tags: ["점심"],
  body: "안녕하세요 가상블로그입니다",
};
const EDITOR_STATE = {
  title: INPUT.title,
  photos: 0,
  fitted_photos: 0,
  stickers: 0,
  maps: 0,
  category: INPUT.category,
  tags: INPUT.tags,
  saved_count: 4,
};
const cleanups: Array<() => unknown> = [];

afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

/** 잠금과 대기 중인 상태 파일, 입력 파일을 준비한 작업 하나. */
async function queuedJob() {
  const dir = await mkdtemp(join(tmpdir(), "naver-blog-worker-"));
  cleanups.push(() => rm(dir, { recursive: true, force: true }));
  const jobId = randomUUID();
  await acquireLock(dir, CDP_URL, jobId);
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
  await writeInput(dir, jobId, INPUT);
  const run = (fake: typeof runDraft, limitMs = 5_000) =>
    runWorker(stateFile(dir, jobId), { runDraft: fake, limitMs, env: ENV });
  return { dir, jobId, run };
}

/** 작업이 끝나면 입력 파일과 잠금이 없고 상태 파일과 끝냄 표시만 남는다. */
async function expectOnlyState(dir: string, jobId: string) {
  expect((await readdir(dir)).sort()).toEqual([`${jobId}.finished`, `${jobId}.json`]);
}

test("성공하면 succeeded 와 편집기 상태, 저장 전후 수를 남기고 연결 값은 env 에서 읽는다", async () => {
  const { dir, jobId, run } = await queuedJob();
  let received: unknown;

  await run(async (env, blocks, input, onStage) => {
    received = { env, blocks, input };
    await onStage("open");
    await onStage("save_clicking");
    return { state: EDITOR_STATE, savedBefore: 3, savedAfter: 4 };
  });

  const state = await readState(dir, jobId);
  expect(state).toMatchObject({
    status: "succeeded",
    save_clicked: true,
    pid: process.pid,
    result: { state: EDITOR_STATE, saved_before: 3, saved_after: 4 },
    error: null,
  });
  expect(state?.finished_at).not.toBeNull();
  expect(received).toEqual({
    env: ENV,
    blocks: [{ type: "text", line: INPUT.body }],
    input: INPUT,
  });
  await expectOnlyState(dir, jobId);
});

test("저장 확인이 save_unconfirmed 로 끝나면 unknown 이다", async () => {
  const { dir, jobId, run } = await queuedJob();

  await run(async (_env, _blocks, _input, onStage) => {
    await onStage("save_clicking");
    throw new EditorError("save_unconfirmed", "save", "임시저장 수가 늘지 않았다");
  });

  const state = await readState(dir, jobId);
  expect(state?.status).toBe("unknown");
  expect(state?.save_clicked).toBe(true);
  expect(state?.error).toMatchObject({ code: "save_unconfirmed", stage: "save" });
  await expectOnlyState(dir, jobId);
});

test("save_clicking 을 알린 뒤 일반 예외가 나도 unknown 이고 예외 원문을 싣지 않는다", async () => {
  const { dir, jobId, run } = await queuedJob();

  await run(async (_env, _blocks, _input, onStage) => {
    await onStage("save_clicking");
    throw new Error("socket closed with secret detail");
  });

  const state = await readState(dir, jobId);
  expect(state?.status).toBe("unknown");
  expect(state?.error).toEqual({ code: "editor_failed", stage: "save" });
  await expectOnlyState(dir, jobId);
});

test("저장 전의 category_not_found 는 failed 와 있는 카테고리 이름을 남긴다", async () => {
  const { dir, jobId, run } = await queuedJob();

  await run(async (_env, _blocks, _input, onStage) => {
    await onStage("settings");
    throw new EditorError("category_not_found", "settings", "카테고리를 찾지 못했다", {
      categories: ["일상", "여행"],
    });
  });

  const state = await readState(dir, jobId);
  expect(state?.status).toBe("failed");
  expect(state?.save_clicked).toBe(false);
  expect(state?.error).toEqual({
    code: "category_not_found",
    stage: "settings",
    message: "카테고리를 찾지 못했다",
    categories: ["일상", "여행"],
  });
  await expectOnlyState(dir, jobId);
});

test("limitMs 가 지나면 signal 로 멈추고 timeout 실패로 끝난다", async () => {
  const { dir, jobId, run } = await queuedJob();
  let aborted = false;

  await run(async (_env, _blocks, _input, onStage, signal) => {
    await onStage("photos");
    await new Promise((_, reject) =>
      signal.addEventListener("abort", () => {
        aborted = true;
        reject(new EditorError("editor_failed", "photos", "aborted"));
      }),
    );
    throw new Error("unreachable");
  }, 100);

  const state = await readState(dir, jobId);
  expect(aborted).toBe(true);
  expect(state?.status).toBe("failed");
  expect(state?.error).toEqual({ code: "timeout", stage: "photos" });
  await expectOnlyState(dir, jobId);
});

test("정리가 먼저 작업을 끝냈으면 save_clicking 이 실패해 저장 단추를 누르지 않는다", async () => {
  const { dir, jobId, run } = await queuedJob();
  let clicked = false;

  await run(async (_env, _blocks, _input, onStage) => {
    await finishState(dir, jobId, { status: "failed", error: { code: "timeout" } });
    await onStage("save_clicking");
    clicked = true;
    return { state: null, savedBefore: 3, savedAfter: 4 };
  });

  const state = await readState(dir, jobId);
  expect(clicked).toBe(false);
  expect(state?.status).toBe("failed");
  expect(state?.error).toEqual({ code: "timeout" });
  expect(state?.save_clicked).toBe(false);
  await expectOnlyState(dir, jobId);
});

test("작업 디렉터리 확인이 실패해도 editor_failed 로 끝내고 자기 잠금을 푼다", async () => {
  const { dir, jobId, run } = await queuedJob();
  // 모드가 700 이 아니면 작업 디렉터리 확인이 실패한다. 소유자는 여전히 쓸 수 있다.
  await chmod(dir, 0o750);
  let called = false;

  await run(async () => {
    called = true;
    return { state: null, savedBefore: 3, savedAfter: 4 };
  });

  const state = await readState(dir, jobId);
  expect(called).toBe(false);
  expect(state?.status).toBe("failed");
  expect(state?.error).toEqual({ code: "editor_failed", stage: "queued" });
  await expectOnlyState(dir, jobId);
});
