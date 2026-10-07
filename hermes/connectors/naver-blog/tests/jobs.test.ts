import { afterEach, expect, test } from "bun:test";
import { randomUUID } from "node:crypto";
import { chmod, mkdir, mkdtemp, readdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { RunStage } from "../src/editor/run.ts";
import {
  acquireLock,
  checkJobDir,
  cleanupJobDir,
  createState,
  finishStale,
  type JobState,
  lockFile,
  readState,
  stateFile,
  updateState,
  writeInput,
} from "../src/jobs.ts";
import { runWorker } from "../src/worker.ts";

const JOBS = join(import.meta.dir, "../src/jobs.ts");
const CDP_A = "http://127.0.0.1:9";
const CDP_B = "http://127.0.0.1:10";
const cleanups: Array<() => unknown> = [];

afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

async function jobDir() {
  const dir = await mkdtemp(join(tmpdir(), "naver-blog-jobs-"));
  cleanups.push(() => rm(dir, { recursive: true, force: true }));
  return dir;
}

function running(jobId: string, overrides: Partial<JobState> = {}): JobState {
  return {
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
    ...overrides,
  };
}

/** 잠금의 생성 시각을 1분 전으로 돌린다. 판정이 「만든 지 10초 안」 에 기대지 않게 한다. */
async function ageLock(dir: string, cdpUrl: string, jobId: string) {
  await writeFile(
    lockFile(dir, cdpUrl),
    JSON.stringify({ job_id: jobId, created_at: new Date(Date.now() - 60_000).toISOString() }),
  );
}

/** 이미 끝나 회수된 프로세스의 pid. */
async function deadPid() {
  const child = Bun.spawn([process.execPath, "-e", ""]);
  await child.exited;
  return child.pid;
}

const errorCode = (work: Promise<unknown>) =>
  work.then(
    () => "resolved",
    (error) => (error as { code?: string }).code,
  );

test("잠금을 만든 프로세스가 끝나도 작업 프로세스가 살아 있으면 같은 브라우저의 둘째 작업은 NAVER_BLOG_BUSY 다", async () => {
  const dir = await jobDir();
  const first = randomUUID();
  // MCP 서버처럼 잠금과 상태 파일을 만들고 바로 끝나는 프로세스.
  const creator = Bun.spawn([
    process.execPath,
    "-e",
    `import { acquireLock, createState } from ${JSON.stringify(JOBS)};
     await acquireLock(${JSON.stringify(dir)}, ${JSON.stringify(CDP_A)}, ${JSON.stringify(first)});
     await createState(${JSON.stringify(dir)}, ${JSON.stringify(running(first))});`,
  ]);
  expect(await creator.exited).toBe(0);
  const worker = Bun.spawn([process.execPath, "-e", "await Bun.sleep(30000)"]);
  cleanups.push(() => worker.kill());
  await updateState(dir, first, { pid: worker.pid, heartbeat_at: new Date().toISOString() });
  await ageLock(dir, CDP_A, first);

  expect(await errorCode(acquireLock(dir, CDP_A, randomUUID()))).toBe("NAVER_BLOG_BUSY");
  expect(await errorCode(acquireLock(dir, `${CDP_A}/`, randomUUID()))).toBe("NAVER_BLOG_BUSY");
  expect(await errorCode(acquireLock(dir, CDP_B, randomUUID()))).toBe("resolved");
  expect((await readState(dir, first))?.status).toBe("running");
});

test("pid 가 사라진 작업의 잠금은 묵은 잠금으로 풀리고 그 작업은 timeout 실패로 끝난다", async () => {
  const dir = await jobDir();
  const first = randomUUID();
  await acquireLock(dir, CDP_A, first);
  await createState(
    dir,
    running(first, { stage: "photos", pid: await deadPid(), heartbeat_at: new Date().toISOString() }),
  );
  await ageLock(dir, CDP_A, first);
  const second = randomUUID();

  await acquireLock(dir, CDP_A, second);

  const stale = await readState(dir, first);
  expect(stale?.status).toBe("failed");
  expect(stale?.error).toEqual({ code: "timeout", stage: "photos" });
  expect(stale?.finished_at).not.toBeNull();
  expect(JSON.parse(await readFile(lockFile(dir, CDP_A), "utf8")).job_id).toBe(second);
});

test("저장 단추를 누른 뒤 사라진 작업은 unknown 으로 끝난다", async () => {
  const dir = await jobDir();
  const first = randomUUID();
  await acquireLock(dir, CDP_A, first);
  await createState(
    dir,
    running(first, {
      stage: "save",
      save_clicked: true,
      pid: await deadPid(),
      heartbeat_at: new Date().toISOString(),
    }),
  );
  await ageLock(dir, CDP_A, first);

  await acquireLock(dir, CDP_A, randomUUID());

  const stale = await readState(dir, first);
  expect(stale?.status).toBe("unknown");
  expect(stale?.save_clicked).toBe(true);
});

test("pid 를 적기 전의 잠금은 만든 지 10초 안이면 살아 있다", async () => {
  const dir = await jobDir();
  const first = randomUUID();
  await acquireLock(dir, CDP_A, first);

  expect(await errorCode(acquireLock(dir, CDP_A, randomUUID()))).toBe("NAVER_BLOG_BUSY");

  await ageLock(dir, CDP_A, first);
  expect(await errorCode(acquireLock(dir, CDP_A, randomUUID()))).toBe("resolved");
});

test("succeeded 로 끝난 상태 위에 정리가 timeout 을 쓰려 해도 그대로다", async () => {
  const dir = await jobDir();
  const jobId = randomUUID();
  const snapshot = running(jobId, { stage: "state" });
  const finished: JobState = {
    ...snapshot,
    status: "succeeded",
    finished_at: new Date().toISOString(),
    result: { saved_before: 3, saved_after: 4 },
  };
  await createState(dir, finished);
  const before = await readFile(stateFile(dir, jobId), "utf8");

  await finishStale(dir, snapshot);
  await updateState(dir, jobId, { stage: "open" });

  expect(await readFile(stateFile(dir, jobId), "utf8")).toBe(before);
});

test("모드 755 인 작업 디렉터리는 NAVER_BLOG_UNAVAILABLE 로 거절한다", async () => {
  const dir = await jobDir();
  const shared = join(dir, "shared");
  await mkdir(shared);
  await chmod(shared, 0o755);

  expect(await errorCode(checkJobDir(shared))).toBe("NAVER_BLOG_UNAVAILABLE");
});

test("없는 작업 디렉터리는 모드 700 으로 만든다", async () => {
  const dir = join(await jobDir(), "fresh");

  await checkJobDir(dir);

  expect((await stat(dir)).mode & 0o777).toBe(0o700);
});

test("끝난 지 24시간이 지난 상태 파일과 끝난 작업의 입력 파일을 지운다", async () => {
  const dir = await jobDir();
  const day = 24 * 60 * 60_000;
  const old = randomUUID();
  const recent = randomUUID();
  const active = randomUUID();
  const finishedAt = (ago: number) => new Date(Date.now() - ago).toISOString();
  await createState(dir, { ...running(old), status: "failed", finished_at: finishedAt(day + 60_000) });
  await createState(dir, { ...running(recent), status: "succeeded", finished_at: finishedAt(day - 60_000) });
  await writeInput(dir, recent, { title: "남은 사본" });
  await createState(dir, running(active, { started_at: finishedAt(2 * day) }));
  await writeInput(dir, active, { title: "도는 작업의 사본" });

  await cleanupJobDir(dir);

  expect((await readdir(dir)).sort()).toEqual(
    [`${active}.input.json`, `${active}.json`, `${recent}.json`].sort(),
  );
});

test("작업 상태 파일에는 초안 본문과 사진 디렉터리가 없다", async () => {
  const dir = await jobDir();
  const jobId = randomUUID();
  const body = "가상국수 본문 한 줄";
  const photoDir = "/example/photos/dir";
  await acquireLock(dir, CDP_A, jobId);
  await createState(dir, running(jobId));
  await writeInput(dir, jobId, {
    title: "가상국수 다녀온 날",
    category: "일상",
    tags: ["점심"],
    body,
    photo_dir: photoDir,
  });
  const snapshots: string[] = [];
  const read = async () => snapshots.push(await readFile(stateFile(dir, jobId), "utf8"));

  await runWorker(stateFile(dir, jobId), {
    env: { NAVER_BLOG_CDP_URL: CDP_A, NAVER_BLOG_ID: "example-blog" },
    runDraft: async (_env, _blocks, _input, onStage) => {
      for (const stage of ["open", "fill", "save", "save_clicking"] as RunStage[]) {
        await onStage(stage);
        await read();
      }
      return { state: null, savedBefore: 1, savedAfter: 2 };
    },
  });
  await read();

  expect(JSON.parse(snapshots.at(-1)!).status).toBe("succeeded");
  for (const snapshot of snapshots) {
    expect(snapshot).not.toContain(body);
    expect(snapshot).not.toContain(photoDir);
    expect(snapshot).not.toContain('"body"');
    expect(snapshot).not.toContain('"photo_dir"');
    expect(snapshot).not.toContain(CDP_A);
  }
});
