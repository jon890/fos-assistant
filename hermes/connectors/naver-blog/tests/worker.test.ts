import { afterEach, expect, test } from "bun:test";
import { randomUUID } from "node:crypto";
import { chmod, mkdir, mkdtemp, readdir, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { OverwriteResult, runOverwrite } from "../src/editor/overwrite.ts";
import { EditorError, type runDraft } from "../src/editor/run.ts";
import {
  acquireLock,
  createState,
  finishState,
  lockFile,
  readState,
  stateFile,
  writeInput,
} from "../src/jobs.ts";
import type { Env } from "../src/session.ts";
import { runWorker, type WorkerDeps } from "../src/worker.ts";
import { GATEWAY_PATH } from "./fake-cdp.ts";
import { fakeRunDraft } from "./fake-worker-entry.ts";

const BLOG_ID = "example-blog";
const ENV = { NAVER_BLOG_BROWSER_URL: `http://127.0.0.1:9${GATEWAY_PATH}`, NAVER_BLOG_ID: BLOG_ID };
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

const JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4]);

/** 잠금과 대기 중인 상태 파일, 입력 파일을 준비한 작업 하나. */
async function queuedJob(input: Record<string, unknown> = INPUT, env: Env = ENV) {
  const dir = await mkdtemp(join(tmpdir(), "naver-blog-worker-"));
  cleanups.push(() => rm(dir, { recursive: true, force: true }));
  const jobId = randomUUID();
  await acquireLock(dir, BLOG_ID, jobId);
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
  await writeInput(dir, jobId, input as typeof INPUT);
  const run = (fake: typeof runDraft, limitMs = 5_000) =>
    runWorker(stateFile(dir, jobId), { runDraft: fake, limitMs, env });
  const runWith = (overrides: Partial<WorkerDeps>, limitMs = 5_000) =>
    runWorker(stateFile(dir, jobId), { limitMs, env, ...overrides });
  return { dir, jobId, run, runWith };
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

test("작업 디렉터리 확인이 실패하면 상태를 쓰지 않고 입력과 잠금도 지우지 않은 채 끝낸다", async () => {
  const { dir, jobId, run } = await queuedJob();
  // 모드가 700 이 아니면 작업 디렉터리 확인이 실패한다. 소유자는 여전히 쓸 수 있다.
  await chmod(dir, 0o750);
  const before = await readState(dir, jobId);
  let called = false;

  await run(async () => {
    called = true;
    return { state: null, savedBefore: 3, savedAfter: 4 };
  });

  expect(called).toBe(false);
  expect(await readState(dir, jobId)).toEqual(before);
  expect((await readdir(dir)).sort()).toEqual(
    [`${jobId}.input.json`, `${jobId}.json`, lockFile(dir, BLOG_ID).slice(dir.length + 1)].sort(),
  );
});

/** 주인의 첨부 디렉터리 아래에 사진 한 장을 둔 사진 디렉터리. */
async function ownerPhotos() {
  // 링크가 없도록 실제 경로를 쓴다. 커넥터가 링크를 거절한다.
  const attachmentDir = await realpath(await mkdtemp(join(tmpdir(), "naver-blog-worker-photos-")));
  cleanups.push(() => rm(attachmentDir, { recursive: true, force: true }));
  const photoDir = join(attachmentDir, "conversation");
  await mkdir(photoDir);
  await writeFile(join(photoDir, "101.jpg"), JPEG);
  const input = { ...INPUT, body: "[사진 1: 101.jpg]", photo_dir: photoDir };
  return { attachmentDir, input };
}

test("작업 프로세스는 자기 env 의 첨부 디렉터리 아래 사진을 다시 검사해 읽는다", async () => {
  const { attachmentDir, input } = await ownerPhotos();
  const { dir, jobId, run } = await queuedJob(input, { ...ENV, NAVER_BLOG_ATTACHMENT_DIR: attachmentDir });

  await run(fakeRunDraft);

  const state = await readState(dir, jobId);
  expect(state?.status).toBe("succeeded");
  expect(state?.result).toMatchObject({ state: { photos: 1 } });
});

test("작업 프로세스의 env 에 첨부 디렉터리가 없으면 사진을 읽지 않고 실패한다", async () => {
  const { input } = await ownerPhotos();
  const { dir, jobId, run } = await queuedJob(input, ENV);

  await run(fakeRunDraft);

  const state = await readState(dir, jobId);
  expect(state?.status).toBe("failed");
  expect(state?.error).toEqual({ code: "editor_failed", stage: "open" });
  expect(JSON.stringify(state)).not.toContain(input.photo_dir);
});

const OVERWRITE_INPUT = {
  draft_id: "224000000001",
  revision: "0123456789abcdef",
  changes: "제목: 가상국수 다녀온 날 → 가상국수 다시 간 날",
  title: "가상국수 다시 간 날",
  category: INPUT.category,
  tags: INPUT.tags,
  body: "가상국수에 또 다녀왔어요\n[기존 사진 1]",
};
const OVERWRITE_RESULT: OverwriteResult = {
  draft_id: OVERWRITE_INPUT.draft_id,
  backup_draft_id: "224000000002",
  backup_title: "[덮어쓰기 전 원본] 가상국수 다녀온 날",
  saved_before: 5,
  saved_after: 5,
  state: { revision: "fedcba9876543210" },
};

/** 받은 초안을 남기는 새 글 대역. 덮어쓰기 작업이 새 글 흐름을 타지 않는지 본다. */
function draftSpy() {
  const calls: unknown[] = [];
  const fake: typeof runDraft = async (_env, _blocks, input) => {
    calls.push(input);
    return { state: null, savedBefore: 3, savedAfter: 4 };
  };
  return { calls, fake };
}

/** 받은 인자를 남기고 정한 결과를 돌려주는 덮어쓰기 대역. */
function overwriteSpy(body: typeof runOverwrite = async () => OVERWRITE_RESULT) {
  const calls: unknown[] = [];
  const fake: typeof runOverwrite = async (env, input, onStage, signal) => {
    calls.push({ env, input });
    return body(env, input, onStage, signal);
  };
  return { calls, fake };
}

test("kind 가 overwrite 인 입력 파일이면 runOverwrite 만 부르고 그 결과를 그대로 남긴다", async () => {
  const { dir, jobId, runWith } = await queuedJob({ kind: "overwrite", ...OVERWRITE_INPUT });
  const draft = draftSpy();
  const overwrite = overwriteSpy(async (_env, _input, onStage) => {
    await onStage("open");
    await onStage("save_clicking");
    return OVERWRITE_RESULT;
  });

  await runWith({ runDraft: draft.fake, runOverwrite: overwrite.fake });

  expect(draft.calls).toEqual([]);
  // 입력 파일의 kind 는 넘기지 않고 일곱 칸만 넘긴다.
  expect(overwrite.calls).toEqual([{ env: ENV, input: OVERWRITE_INPUT }]);
  expect(await readState(dir, jobId)).toMatchObject({
    status: "succeeded",
    save_clicked: true,
    result: OVERWRITE_RESULT,
    error: null,
  });
  await expectOnlyState(dir, jobId);
});

test("kind 가 없는 옛 입력 파일은 새 글 저장으로 읽어 runDraft 를 부른다", async () => {
  const { dir, jobId, runWith } = await queuedJob(INPUT);
  const draft = draftSpy();
  const overwrite = overwriteSpy();

  await runWith({ runDraft: draft.fake, runOverwrite: overwrite.fake });

  expect(draft.calls).toEqual([INPUT]);
  expect(overwrite.calls).toEqual([]);
  expect(await readState(dir, jobId)).toMatchObject({
    status: "succeeded",
    result: { state: null, saved_before: 3, saved_after: 4 },
  });
});

test("덮어쓰기 입력 파일에 칸이 빠졌으면 어느 흐름도 부르지 않고 editor_failed 다", async () => {
  const { changes: _changes, ...missing } = OVERWRITE_INPUT;
  const { dir, jobId, runWith } = await queuedJob({ kind: "overwrite", ...missing });
  const draft = draftSpy();
  const overwrite = overwriteSpy();

  await runWith({ runDraft: draft.fake, runOverwrite: overwrite.fake });

  expect(draft.calls).toEqual([]);
  expect(overwrite.calls).toEqual([]);
  expect(await readState(dir, jobId)).toMatchObject({
    status: "failed",
    error: { code: "editor_failed", stage: "queued" },
  });
  await expectOnlyState(dir, jobId);
});

test("runOverwrite 가 save_clicking 뒤 사본 번호를 실은 EditorError 를 던지면 unknown 이고 오류에 사본 번호가 실린다", async () => {
  const { dir, jobId, runWith } = await queuedJob({ kind: "overwrite", ...OVERWRITE_INPUT });
  const overwrite = overwriteSpy(async (_env, _input, onStage) => {
    await onStage("save");
    await onStage("save_clicking");
    throw new EditorError("save_unconfirmed", "save", "저장을 확인하지 못했다", {
      backup_draft_id: "224000000002",
    });
  });

  await runWith({ runDraft: draftSpy().fake, runOverwrite: overwrite.fake });

  const state = await readState(dir, jobId);
  expect(state?.status).toBe("unknown");
  expect(state?.save_clicked).toBe(true);
  expect(state?.error).toEqual({
    code: "save_unconfirmed",
    stage: "save",
    message: "저장을 확인하지 못했다",
    backup_draft_id: "224000000002",
  });
  await expectOnlyState(dir, jobId);
});

test("덮어쓰기가 시간 상한에 걸려 EditorError 로 끝나면 timeout 오류에 사본 번호만 더한다", async () => {
  const { dir, jobId, runWith } = await queuedJob({ kind: "overwrite", ...OVERWRITE_INPUT });
  const overwrite = overwriteSpy(async (_env, _input, onStage, signal) => {
    await onStage("apply");
    return new Promise<OverwriteResult>((_, reject) =>
      signal.addEventListener("abort", () =>
        reject(
          new EditorError("editor_failed", "apply", "aborted", {
            aborted: true,
            backup_draft_id: "224000000002",
          }),
        ),
      ),
    );
  });

  await runWith({ runDraft: draftSpy().fake, runOverwrite: overwrite.fake }, 100);

  const state = await readState(dir, jobId);
  expect(state?.status).toBe("failed");
  expect(state?.error).toEqual({ code: "timeout", stage: "apply", backup_draft_id: "224000000002" });
  await expectOnlyState(dir, jobId);
});
