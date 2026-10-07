import { afterEach, expect, test } from "bun:test";
import { randomUUID } from "node:crypto";
import {
  chmod,
  mkdir,
  mkdtemp,
  readdir,
  readFile,
  rm,
  stat,
  utimes,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { RunStage } from "../src/editor/run.ts";
import {
  acquireLock,
  checkJobDir,
  cleanupJobDir,
  createState,
  finishedFile,
  finishStale,
  finishState,
  jobAlive,
  type JobState,
  lockFile,
  readState,
  releaseLock,
  removeStaleLock,
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

/** 30초 넘게 갱신하지 않은 작업의 `heartbeat_at`. */
const staleHeartbeat = () => new Date(Date.now() - 31_000).toISOString();

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

test("pid 가 없는 프로세스여도 방금 갱신한 작업은 살아 있어 같은 브라우저의 둘째 작업은 NAVER_BLOG_BUSY 다", async () => {
  // 승인한 호출과 MCP 서버의 PID 네임스페이스가 다르면 살아 있는 작업 프로세스도 `kill(pid, 0)` 에 없다고 나온다.
  const dir = await jobDir();
  const first = randomUUID();
  await acquireLock(dir, CDP_A, first);
  await createState(
    dir,
    running(first, { stage: "photos", pid: await deadPid(), heartbeat_at: new Date().toISOString() }),
  );
  await ageLock(dir, CDP_A, first);

  expect(await errorCode(acquireLock(dir, CDP_A, randomUUID()))).toBe("NAVER_BLOG_BUSY");
  expect((await readState(dir, first))?.status).toBe("running");
});

test("jobAlive 는 pid 가 있는 작업을 마지막 갱신에서 30초 안이고 시작한 지 11분 안일 때만 살아 있다고 본다", async () => {
  const now = Date.parse("2026-01-01T00:20:00.000Z");
  const at = (ms: number) => new Date(now - ms).toISOString();
  const pid = await deadPid();
  const job = (heartbeatAgo: number, startedAgo = 60_000) =>
    running(randomUUID(), { pid, heartbeat_at: at(heartbeatAgo), started_at: at(startedAgo) });

  expect(jobAlive(job(0), 0, now)).toBe(true);
  expect(jobAlive(job(29_999), 0, now)).toBe(true);
  expect(jobAlive(job(30_000), 0, now)).toBe(false);
  expect(jobAlive(job(31_000), 0, now)).toBe(false);
  expect(jobAlive(job(0, 11 * 60_000), 0, now)).toBe(false);
  expect(jobAlive({ ...job(0), heartbeat_at: null }, 0, now)).toBe(false);
});

test("30초 넘게 갱신하지 않은 작업의 잠금은 묵은 잠금으로 풀리고 그 작업은 timeout 실패로 끝난다", async () => {
  const dir = await jobDir();
  const first = randomUUID();
  await acquireLock(dir, CDP_A, first);
  await createState(
    dir,
    running(first, { stage: "photos", pid: await deadPid(), heartbeat_at: staleHeartbeat() }),
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
      heartbeat_at: staleHeartbeat(),
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

test("끝난 지 24시간이 지난 상태 파일과 끝냄 표시, 끝난 작업의 입력 파일을 지운다", async () => {
  const dir = await jobDir();
  const day = 24 * 60 * 60_000;
  const old = randomUUID();
  const recent = randomUUID();
  const active = randomUUID();
  const finishedAt = (ago: number) => new Date(Date.now() - ago).toISOString();
  await createState(dir, { ...running(old), status: "failed", finished_at: finishedAt(day + 60_000) });
  await writeFile(finishedFile(dir, old), "");
  const oldTime = new Date(Date.now() - day - 60_000);
  await utimes(finishedFile(dir, old), oldTime, oldTime);
  await createState(dir, { ...running(recent), status: "succeeded", finished_at: finishedAt(day - 60_000) });
  await writeFile(finishedFile(dir, recent), "");
  await writeInput(dir, recent, { title: "남은 사본" });
  await createState(dir, running(active, { started_at: finishedAt(2 * day) }));
  await writeInput(dir, active, { title: "도는 작업의 사본" });

  await cleanupJobDir(dir);

  expect((await readdir(dir)).sort()).toEqual(
    [`${active}.input.json`, `${active}.json`, `${recent}.finished`, `${recent}.json`].sort(),
  );
});

test("이미 있는 상태 파일 위에 createState 는 EEXIST 로 실패하고 원래 파일을 둔다", async () => {
  const dir = await jobDir();
  const jobId = randomUUID();
  await createState(dir, running(jobId, { stage: "photos" }));

  expect(await errorCode(createState(dir, running(jobId)))).toBe("EEXIST");

  expect((await readState(dir, jobId))?.stage).toBe("photos");
  expect(await readdir(dir)).toEqual([`${jobId}.json`]);
});

test("정리가 끝냄 표시를 먼저 만들면 작업 프로세스의 heartbeat 와 끝내기가 그 결과를 덮지 못한다", async () => {
  const dir = await jobDir();
  const jobId = randomUUID();
  const snapshot = running(jobId, { stage: "photos", pid: 1, heartbeat_at: new Date().toISOString() });
  await createState(dir, snapshot);
  // 다른 프로세스의 정리가 끝냄 표시를 만들었고, 아직 상태는 쓰지 않은 순간.
  await writeFile(finishedFile(dir, jobId), "");

  const heartbeat = await updateState(dir, jobId, { heartbeat_at: "2026-01-01T00:00:00.000Z" });
  const finished = await finishState(dir, jobId, { status: "succeeded", result: { saved_after: 4 } });

  expect(heartbeat).toEqual(snapshot);
  expect(finished).toEqual(snapshot);
  expect(await readState(dir, jobId)).toEqual(snapshot);
});

test("끝냄 표시를 만든 정리가 쓴 timeout 위에 작업 프로세스의 heartbeat 가 running 을 되살리지 못한다", async () => {
  const dir = await jobDir();
  const jobId = randomUUID();
  const snapshot = running(jobId, { stage: "photos", pid: 1, heartbeat_at: new Date().toISOString() });
  await createState(dir, snapshot);

  await finishStale(dir, snapshot);
  await updateState(dir, jobId, { heartbeat_at: new Date().toISOString(), stage: "components" });
  await finishState(dir, jobId, { status: "succeeded" });

  const state = await readState(dir, jobId);
  expect(state?.status).toBe("failed");
  expect(state?.stage).toBe("photos");
  expect(state?.error).toEqual({ code: "timeout", stage: "photos" });
  expect(await readdir(dir)).toContain(`${jobId}.finished`);
});

test("묵은 잠금 정리는 판정한 뒤 다른 job_id 로 다시 만들어진 잠금을 지우지 않는다", async () => {
  const dir = await jobDir();
  const stale = randomUUID();
  const fresh = randomUUID();
  // 묵었다고 판정한 잠금은 이미 다른 호출이 지웠고, 그 자리에 새 작업이 잠금을 잡았다.
  await acquireLock(dir, CDP_A, fresh);
  const before = await readFile(lockFile(dir, CDP_A), "utf8");

  await removeStaleLock(lockFile(dir, CDP_A), stale);

  expect(await readFile(lockFile(dir, CDP_A), "utf8")).toBe(before);
  expect(await readdir(dir)).toEqual([lockFile(dir, CDP_A).slice(dir.length + 1)]);

  await removeStaleLock(lockFile(dir, CDP_A), fresh);
  expect(await readdir(dir)).toEqual([]);
});

test("끝냄 표시를 만들고 10초 넘게 상태를 쓰지 않았으면 그 쪽이 죽은 것으로 보고 대신 끝낸다", async () => {
  const dir = await jobDir();
  const jobId = randomUUID();
  await createState(dir, running(jobId, { stage: "photos", pid: 1 }));
  // 다른 프로세스가 끝냄 표시만 만들고 상태를 쓰기 전에 죽었다.
  await writeFile(finishedFile(dir, jobId), "");
  const abandoned = new Date(Date.now() - 11_000);
  await utimes(finishedFile(dir, jobId), abandoned, abandoned);

  const finished = await finishState(dir, jobId, { status: "succeeded", result: { saved_after: 4 } });

  expect(finished?.status).toBe("succeeded");
  expect(await readState(dir, jobId)).toMatchObject({
    status: "succeeded",
    stage: "photos",
    result: { saved_after: 4 },
  });
});

test("24시간이 지난 고아 임시 파일과 되돌리지 못한 잠금만 지우고, 그 작업의 releaseLock 은 남긴 잠금도 지운다", async () => {
  const dir = await jobDir();
  const day = 24 * 60 * 60_000;
  const lock = lockFile(dir, CDP_A).slice(dir.length + 1);
  const jobId = randomUUID();
  const oldTemporary = `${randomUUID()}.json.${randomUUID()}.tmp`;
  const oldStale = `${lock}.${randomUUID()}.stale`;
  const oldKept = `${lock}.stale-${randomUUID()}`;
  const recentKept = `${lock}.stale-${jobId}`;
  const oldTime = new Date(Date.now() - day - 60_000);
  for (const name of [oldTemporary, oldStale, oldKept]) {
    await writeFile(join(dir, name), JSON.stringify({ job_id: randomUUID() }));
    await utimes(join(dir, name), oldTime, oldTime);
  }
  await writeFile(join(dir, recentKept), JSON.stringify({ job_id: jobId }));

  await cleanupJobDir(dir);
  expect(await readdir(dir)).toEqual([recentKept]);

  await releaseLock(dir, jobId);
  expect(await readdir(dir)).toEqual([]);
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
