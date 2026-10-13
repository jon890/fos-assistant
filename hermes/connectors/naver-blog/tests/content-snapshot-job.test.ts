import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { afterEach, expect, test } from "bun:test";
import { randomUUID } from "node:crypto";
import {
  mkdtemp,
  readdir,
  readFile,
  realpath,
  rm,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { CompiledSnapshotV1 } from "../src/content-draft.ts";
import {
  createSnapshotServer,
  snapshotOperation,
} from "../src/content-snapshot.ts";
import type {
  SnapshotJobDeps,
  SnapshotJobState,
  SnapshotStamp,
} from "../src/content-snapshot-job.ts";
import {
  acquireLock,
  createState,
  type JobState,
  lockFile,
  readState,
  stateFile,
  updateState,
} from "../src/jobs.ts";
import { createServer } from "../src/server.ts";
import type { Env } from "../src/session.ts";
import {
  type AuthorizeCall,
  fakeControlPlane,
  stampFor,
  syntheticSnapshot,
} from "./content-snapshot-fixture.ts";

const ENTRY = join(import.meta.dir, "fake-snapshot-worker-entry.ts");
const TICKET = "synthetic.ticket-value_ABC";
const BODY_MARKER = "합성 원고 첫 줄";
const COMPILE_ONLY = { compile: true, start: false, status: false };
const cleanups: Array<() => unknown> = [];
afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

type Decide = (call: AuthorizeCall) => "allow" | "deny" | Response;

async function setup({
  photos = 3,
  order,
  decide,
}: { photos?: number; order?: number[]; decide?: Decide } = {}) {
  const root = await realpath(
    await mkdtemp(join(tmpdir(), "snapshot-job-photos-")),
  );
  const jobDir = await realpath(
    await mkdtemp(join(tmpdir(), "snapshot-job-dir-")),
  );
  cleanups.push(() => rm(root, { recursive: true, force: true }));
  cleanups.push(() => rm(jobDir, { recursive: true, force: true }));
  const cp = fakeControlPlane(decide);
  cleanups.push(() => cp.stop());
  const photoDir = join(root, "conversation");
  const snapshot = await syntheticSnapshot(photoDir, photos, order);
  const env: Env = {
    NAVER_BLOG_BROWSER_URL: "http://127.0.0.1:9/synthetic-gateway",
    NAVER_BLOG_ID: "example-blog",
    NAVER_BLOG_ATTACHMENT_DIR: root,
    NAVER_BLOG_JOB_DIR: jobDir,
    PATH: process.env.PATH,
    HOME: process.env.HOME,
    FOS_CONTENT_SAVE_AUTHORIZE_URL: cp.url,
  };
  const deps: Partial<SnapshotJobDeps> = {
    workerEntry: ENTRY,
    startWaitMs: 5_000,
    checkSession: async () => ({}) as never,
  };
  const connect = async (
    operation: "start" | "status",
    overrides: Partial<SnapshotJobDeps> = {},
  ) => {
    const client = new Client({ name: "snapshot-job-test", version: "1" });
    const server = createSnapshotServer(env, operation, {
      ...deps,
      ...overrides,
    });
    const [left, right] = InMemoryTransport.createLinkedPair();
    await server.connect(right);
    await client.connect(left);
    cleanups.push(() => server.close());
    cleanups.push(() => client.close());
    return client;
  };
  const call = async (
    operation: "start" | "status",
    args: unknown,
    overrides: Partial<SnapshotJobDeps> = {},
  ) => {
    const client = await connect(operation, overrides);
    const name =
      operation === "start"
        ? "start_content_snapshot"
        : "content_snapshot_status";
    const result = await client.callTool({
      name,
      arguments: args as Record<string, unknown>,
    });
    const text = (result.content as Array<{ text: string }>)[0]!.text;
    // 결과와 오류에 ticket·본문·경로·CP 주소가 실리지 않는다.
    for (const secret of [TICKET, BODY_MARKER, root, jobDir, cp.url])
      expect(text).not.toContain(secret);
    return { isError: result.isError === true, body: JSON.parse(text) };
  };
  const counts = async (jobId: string) => {
    const lines = async (suffix: string) =>
      (
        await readFile(`${stateFile(jobDir, jobId)}.${suffix}`, "utf8").catch(
          () => "",
        )
      )
        .split("\n")
        .filter(Boolean);
    return {
      uploads: await lines("uploads"),
      clicks: (await lines("clicks")).length,
    };
  };
  const finished = async (stamp: SnapshotStamp) => {
    const deadline = Date.now() + 10_000;
    for (;;) {
      const status = await call("status", { stamp });
      if (!status.isError && status.body.status !== "running")
        return status.body;
      if (Date.now() > deadline) throw new Error("job did not finish");
      await Bun.sleep(50);
    }
  };
  return { env, jobDir, root, cp, snapshot, call, counts, finished };
}

const startArgs = (snapshot: CompiledSnapshotV1, stamp: SnapshotStamp) => ({
  snapshot,
  jobId: stamp.jobId,
  ticket: TICKET,
  stamp,
});

test("세션 확인 중 10초 지난 예약의 status 는 running 이고, 확인 뒤 같은 job 을 한 번 시작한다", async () => {
  const { jobDir, cp, snapshot, call, counts, finished } = await setup({
    photos: 1,
  });
  const stamp = stampFor(snapshot);
  let entered!: () => void;
  const checking = new Promise<void>((resolve) => {
    entered = resolve;
  });
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  const start = call("start", startArgs(snapshot, stamp), {
    checkSession: async (_env, options) => {
      expect(options?.timeoutMs).toBe(45_000);
      entered();
      await gate;
      return {} as never;
    },
  });
  await checking;
  try {
    // 실제 시간을 기다리지 않고 예약이 11초 전에 생긴 상태를 만든다.
    await updateState(jobDir, stamp.jobId, {
      started_at: new Date(Date.now() - 11_000).toISOString(),
    });
    expect((await call("status", { stamp })).body).toMatchObject({
      status: "running",
      error_code: null,
    });
    expect(cp.calls).toEqual([]);
  } finally {
    release();
  }
  expect((await start).isError).toBe(false);
  expect(await finished(stamp)).toMatchObject({ status: "succeeded" });
  expect(cp.calls.filter((entry) => entry.stage === "start")).toHaveLength(1);
  expect((await counts(stamp.jobId)).clicks).toBe(1);
});

test("세션 확인 상한과 spawn 여유가 지난 pid 없는 예약은 timeout 으로 끝낸다", async () => {
  const { jobDir, cp, snapshot, call, counts } = await setup({ photos: 1 });
  const stamp = stampFor(snapshot);
  await createState(
    jobDir,
    runningState(stamp.jobId, {
      pid: null,
      stage: "queued",
      snapshot_stamp: stamp,
      started_at: new Date(Date.now() - 56_000).toISOString(),
    }),
  );
  expect((await call("status", { stamp })).body).toMatchObject({
    status: "failed",
    error_code: "timeout",
  });
  expect((await call("start", startArgs(snapshot, stamp))).body.status).toBe(
    "failed",
  );
  expect(cp.calls).toEqual([]);
  expect((await counts(stamp.jobId)).clicks).toBe(0);
});

async function filesContain(dir: string, needle: string) {
  for (const name of await readdir(dir))
    if (
      (await readFile(join(dir, name), "utf8").catch(() => "")).includes(needle)
    )
      return true;
  return false;
}

test("예약 ID로 worker 를 한 번 띄우고 start·photo·save authorize 뒤 같은 job 만 조회한다", async () => {
  const { jobDir, cp, snapshot, call, counts, finished } = await setup({
    photos: 3,
    order: [2, 1, 3],
  });
  const stamp = stampFor(snapshot);
  const started = await call("start", startArgs(snapshot, stamp));
  // 대역 worker 는 빨라서 접수 응답 전에 끝났을 수 있다.
  expect(started).toEqual({
    isError: false,
    body: {
      job_id: stamp.jobId,
      status: expect.stringMatching(/^(running|succeeded)$/),
      stamp,
      error_code: null,
    },
  });
  const status = await finished(stamp);
  expect(status).toEqual({
    job_id: stamp.jobId,
    stamp,
    status: "succeeded",
    stage: "save",
    save_clicked: true,
    finished_at: expect.any(String),
    saved_before: 3,
    saved_after: 4,
    error_code: null,
  });
  // 사진 순서가 asset 번호와 달라도 position 은 canonical block 의 0부터 위치다.
  expect(cp.calls).toEqual([
    { ticket: TICKET, stage: "start" },
    ...snapshot.assetManifest.map((entry) => ({
      ticket: TICKET,
      stage: "photo",
      position: entry.position,
    })),
    { ticket: TICKET, stage: "save" },
  ]);
  expect(snapshot.assetManifest.map((entry) => entry.position)).toEqual([
    1, 2, 3,
  ]);
  const recorded = await counts(stamp.jobId);
  expect(recorded.uploads).toEqual(
    snapshot.assetManifest.map((entry) => entry.sourceFingerprint),
  );
  expect(recorded.clicks).toBe(1);
  // ticket 은 상태·입력 파일에 남지 않고, worker 는 env 에서 지운 뒤 일한다.
  expect(await filesContain(jobDir, TICKET)).toBe(false);
  expect(await filesContain(jobDir, BODY_MARKER)).toBe(false);
  const envBefore = await readFile(
    `${stateFile(jobDir, stamp.jobId)}.env`,
    "utf8",
  );
  const envAfter = await readFile(
    `${stateFile(jobDir, stamp.jobId)}.env-after`,
    "utf8",
  );
  expect(envBefore).toContain("FOS_CONTENT_SAVE_TICKET");
  expect(envAfter).not.toContain("FOS_CONTENT_SAVE_TICKET");
  expect(envAfter).not.toContain("FOS_CONTENT_SAVE_AUTHORIZE_URL");

  // 같은 예약 ID 를 다시 보내도 새 worker 를 띄우지 않고 기존 상태만 돌려준다.
  const again = await call("start", startArgs(snapshot, stamp));
  expect(again.body).toEqual({
    job_id: stamp.jobId,
    status: "succeeded",
    stamp,
    error_code: null,
  });
  await Bun.sleep(300);
  expect(cp.calls.filter((entry) => entry.stage === "start")).toHaveLength(1);
  expect((await counts(stamp.jobId)).clicks).toBe(1);
});

test("같은 예약 ID 의 동시 start 는 worker 하나만 띄우고, 뒤이은 다른 stamp 는 결과 없이 거절한다", async () => {
  const { call, cp, counts, finished, snapshot } = await setup({ photos: 1 });
  const stamp = stampFor(snapshot);
  const results = await Promise.all(
    [0, 1, 2].map(() => call("start", startArgs(snapshot, stamp))),
  );
  for (const result of results) {
    expect(result.isError).toBe(false);
    expect(result.body.job_id).toBe(stamp.jobId);
  }
  expect(await finished(stamp)).toMatchObject({ status: "succeeded" });
  const other = { ...stamp, bindingId: "99" };
  expect(await call("start", startArgs(snapshot, other))).toEqual({
    isError: true,
    body: { error: "not_found" },
  });
  await Bun.sleep(300);
  expect(cp.calls.filter((entry) => entry.stage === "start")).toHaveLength(1);
  expect((await counts(stamp.jobId)).clicks).toBe(1);
}, 20_000);

for (const [name, deniedAt, uploads] of [
  ["start", (call: AuthorizeCall) => call.stage === "start", 0],
  [
    "두 번째 사진",
    (call: AuthorizeCall) => call.stage === "photo" && call.position === 2,
    1,
  ],
  ["save", (call: AuthorizeCall) => call.stage === "save", 3],
] as const)
  test(`CP 가 ${name} authorize 를 거절하면 그 뒤 업로드와 저장 클릭이 0회이고 failed 로 끝난다`, async () => {
    const { call, counts, finished, snapshot } = await setup({
      decide: (entry) => (deniedAt(entry) ? "deny" : "allow"),
    });
    const stamp = stampFor(snapshot);
    await call("start", startArgs(snapshot, stamp));
    const status = await finished(stamp);
    expect(status).toMatchObject({
      status: "failed",
      save_clicked: false,
      error_code: "authorize_denied",
    });
    expect(await counts(stamp.jobId)).toEqual({
      uploads: snapshot.assetManifest
        .slice(0, uploads)
        .map((entry) => entry.sourceFingerprint),
      clicks: 0,
    });
  });

test("CP 장애·틀린 응답 모양은 허가가 아니다", async () => {
  for (const answer of [
    () => new Response("", { status: 500 }),
    () => Response.json({ allowed: "true" }),
    () => Response.json({ allowed: true, extra: 1 }),
    () => new Response("not json", { status: 200 }),
  ]) {
    const { call, counts, finished, snapshot } = await setup({
      photos: 1,
      decide: answer,
    });
    const stamp = stampFor(snapshot);
    await call("start", startArgs(snapshot, stamp));
    expect(await finished(stamp)).toMatchObject({
      status: "failed",
      error_code: "authorize_denied",
    });
    expect(await counts(stamp.jobId)).toEqual({ uploads: [], clicks: 0 });
  }
});

test("hash 가 맞아도 tuple·본문이 바뀐 snapshot 은 CP 호출 없이 snapshot_invalid 로 끝난다", async () => {
  const { call, cp, counts, finished, snapshot } = await setup();
  const tampered = {
    ...snapshot,
    payload: { ...snapshot.payload, body: snapshot.payload.body + "\n추가" },
  };
  const stamp = stampFor(snapshot);
  await call("start", startArgs(tampered, stamp));
  expect(await finished(stamp)).toMatchObject({
    status: "failed",
    error_code: "snapshot_invalid",
  });
  expect(cp.calls).toEqual([]);
  expect((await counts(stamp.jobId)).clicks).toBe(0);
});

test("compile 뒤 원본 사진이 바뀌면 그 자리 authorize 와 업로드 없이 photo_mismatch 다", async () => {
  const { call, cp, counts, finished, snapshot, root } = await setup({
    photos: 2,
  });
  await writeFile(
    join(root, "conversation", "2.jpg"),
    new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 9, 9]),
  );
  const stamp = stampFor(snapshot);
  await call("start", startArgs(snapshot, stamp));
  expect(await finished(stamp)).toMatchObject({
    status: "failed",
    error_code: "photo_mismatch",
  });
  expect(cp.calls.map((entry) => entry.position ?? entry.stage)).toEqual([
    "start",
    1,
  ]);
  expect(await counts(stamp.jobId)).toEqual({
    uploads: [snapshot.assetManifest[0]!.sourceFingerprint],
    clicks: 0,
  });
});

test("잠금이 바쁘면 예약 ID 를 failed 로 남기고 다시 보내도 새로 띄우지 않는다", async () => {
  const { call, cp, jobDir, snapshot } = await setup();
  const holder = randomUUID();
  await acquireLock(jobDir, "example-blog", holder);
  await createState(jobDir, {
    ...runningState(holder),
    pid: 1,
    heartbeat_at: new Date().toISOString(),
  });
  const stamp = stampFor(snapshot);
  const first = await call("start", startArgs(snapshot, stamp));
  expect(first.body).toEqual({
    job_id: stamp.jobId,
    status: "failed",
    stamp,
    error_code: "busy",
  });
  expect((await call("status", { stamp })).body).toMatchObject({
    status: "failed",
    error_code: "busy",
    stage: "queued",
  });
  expect(await readFile(lockFile(jobDir, "example-blog"), "utf8")).toContain(
    holder,
  );
  expect((await call("start", startArgs(snapshot, stamp))).body.status).toBe(
    "failed",
  );
  expect(cp.calls).toEqual([]);
});

test("worker 가 접수를 알리지 않으면 start_unknown 이고 예약 ID 를 지우지 않으며 재전송도 새로 띄우지 않는다", async () => {
  const { call, jobDir, snapshot } = await setup();
  const silent = join(jobDir, "..", `silent-${randomUUID()}.ts`);
  await writeFile(silent, "process.exit(0);\n");
  cleanups.push(() => rm(silent, { force: true }));
  const stamp = stampFor(snapshot);
  const first = await call("start", startArgs(snapshot, stamp), {
    workerEntry: silent,
    startWaitMs: 300,
  });
  expect(first).toEqual({ isError: true, body: { error: "start_unknown" } });
  const kept = await readState(jobDir, stamp.jobId);
  expect(kept).toMatchObject({
    status: "running",
    pid: null,
    snapshot_stamp: stamp,
  });
  const again = await call("start", startArgs(snapshot, stamp), {
    workerEntry: ENTRY,
  });
  expect(again.body).toEqual({
    job_id: stamp.jobId,
    status: "running",
    stamp,
    error_code: null,
  });
  await Bun.sleep(300);
  expect(await readState(jobDir, stamp.jobId)).toMatchObject({
    pid: null,
    stage: "queued",
  });
});

test("authorize 주소가 없거나 고정 경로가 아니면 상태를 만들지 않는다", async () => {
  const { call, env, jobDir, snapshot } = await setup();
  for (const url of [
    undefined,
    "http://127.0.0.1:9/other",
    "http://user:pw@127.0.0.1:9/internal/content-draft-jobs/authorize",
    "file:///internal/content-draft-jobs/authorize",
  ]) {
    if (url === undefined) delete env.FOS_CONTENT_SAVE_AUTHORIZE_URL;
    else env.FOS_CONTENT_SAVE_AUTHORIZE_URL = url;
    const stamp = stampFor(snapshot);
    expect(await call("start", startArgs(snapshot, stamp))).toEqual({
      isError: true,
      body: { error: "unavailable" },
    });
    expect(await readState(jobDir, stamp.jobId)).toBeNull();
  }
});

test("start 입력은 strict 하다: jobId·hash 불일치, ticket 형식, 알 수 없는 키", async () => {
  const { call, jobDir, snapshot } = await setup();
  const stamp = stampFor(snapshot);
  for (const args of [
    { ...startArgs(snapshot, stamp), jobId: randomUUID() },
    {
      ...startArgs(snapshot, stamp),
      stamp: { ...stamp, snapshotHash: "0".repeat(64) },
    },
    { ...startArgs(snapshot, stamp), ticket: "two\nlines" },
    { ...startArgs(snapshot, stamp), ticket: "a".repeat(4097) },
    { ...startArgs(snapshot, stamp), mode: "start" },
    { snapshot, jobId: stamp.jobId, ticket: TICKET },
  ])
    expect(await call("start", args)).toEqual({
      isError: true,
      body: { error: "invalid_input" },
    });
  expect(await readState(jobDir, stamp.jobId)).toBeNull();
});

test("start 재조회는 status 와 같은 규칙으로 안전한 code 만 싣는다", async () => {
  const { jobDir, snapshot, call } = await setup({ photos: 1 });
  for (const code of ["busy", "bad-code", "UPPER", "a".repeat(65)]) {
    const stamp = stampFor(snapshot);
    await createState(
      jobDir,
      runningState(stamp.jobId, {
        status: "failed",
        snapshot_stamp: stamp,
        error: { code, message: BODY_MARKER },
      }),
    );
    const result = (await call("start", startArgs(snapshot, stamp))).body;
    expect(result.error_code).toBe(code === "busy" ? "busy" : null);
    expect(result.error_code).toBe(
      (await call("status", { stamp })).body.error_code,
    );
    expect(Object.keys(result).sort()).toEqual([
      "error_code",
      "job_id",
      "stamp",
      "status",
    ]);
  }
});

const runningState = (
  jobId: string,
  extra: Partial<SnapshotJobState> = {},
): SnapshotJobState => ({
  job_id: jobId,
  status: "running",
  stage: "fill",
  save_clicked: false,
  started_at: new Date().toISOString(),
  finished_at: null,
  result: null,
  error: null,
  pid: 4242,
  heartbeat_at: new Date().toISOString(),
  ...extra,
});

async function publicDraftJob(env: Env, jobId: string) {
  const client = new Client({ name: "public", version: "1" });
  const server = createServer(env);
  const [left, right] = InMemoryTransport.createLinkedPair();
  await server.connect(right);
  await client.connect(left);
  try {
    const result = await client.callTool({
      name: "draft_job",
      arguments: { job_id: jobId, wait_seconds: 0 },
    });
    return JSON.parse((result.content as Array<{ text: string }>)[0]!.text);
  } finally {
    await client.close();
    await server.close();
  }
}

test("반례 표: 공개 draft_job 은 stamp 키가 있으면 모두 없는 작업이고 상태를 바꾸지 않는다", async () => {
  const { env, jobDir, snapshot, call } = await setup();
  const NOT_FOUND = { error: { code: "NAVER_BLOG_JOB_NOT_FOUND" } };
  const stamp = stampFor(snapshot);
  const old = new Date(Date.now() - 20 * 60_000).toISOString();
  const cases: Array<[string, Record<string, unknown>]> = [
    ["running", { snapshot_stamp: stamp }],
    [
      "terminal",
      {
        snapshot_stamp: stamp,
        status: "succeeded",
        finished_at: new Date().toISOString(),
      },
    ],
    ["stale", { snapshot_stamp: stamp, started_at: old, heartbeat_at: old }],
    ["null stamp", { snapshot_stamp: null }],
    ["malformed stamp", { snapshot_stamp: { protocol: "x" } }],
  ];
  for (const [, extra] of cases) {
    const jobId = randomUUID();
    await createState(
      jobDir,
      runningState(jobId, extra as Partial<SnapshotJobState>),
    );
    const before = await readFile(stateFile(jobDir, jobId), "utf8");
    expect(await publicDraftJob(env, jobId)).toEqual(NOT_FOUND);
    expect(await readFile(stateFile(jobDir, jobId), "utf8")).toBe(before);
    expect(await readdir(jobDir)).not.toContain(`${jobId}.finished`);
  }
  // legacy 상태는 공개 계약을 유지하고 private 조회로는 읽지 않는다.
  const legacy = randomUUID();
  await createState(jobDir, runningState(legacy));
  expect(await publicDraftJob(env, legacy)).toMatchObject({
    job_id: legacy,
    status: "running",
  });
  expect(
    (await call("status", { stamp: { ...stamp, jobId: legacy } })).body,
  ).toEqual({ error: "not_found" });
});

test("반례 표: private status 는 정확한 stamp 만 받고 다른 profile·binding·hash·버전은 결과 없이 거절한다", async () => {
  const { jobDir, snapshot, call } = await setup();
  const stamp = stampFor(snapshot);
  await createState(
    jobDir,
    runningState(stamp.jobId, { snapshot_stamp: stamp }),
  );
  expect((await call("status", { stamp })).body).toMatchObject({
    status: "running",
    stage: "fill",
  });
  const changed: Array<Partial<SnapshotStamp>> = [
    { profileHash: "f".repeat(64) },
    { bindingId: "40" },
    { connectionId: "43" },
    { bindingUpdatedAt: "2026-10-12T01:02:03.123457Z" },
    { connectionUpdatedAt: "2026-10-12T01:02:04.000002Z" },
    { snapshotHash: "e".repeat(64) },
    { snapshotId: randomUUID() },
  ];
  for (const change of changed)
    expect(
      (await call("status", { stamp: { ...stamp, ...change } })).body,
    ).toEqual({ error: "not_found" });
  for (const args of [
    { stamp: null },
    {},
    { stamp: { ...stamp, extra: "x" } },
    { stamp: { ...stamp, bindingId: 41 } },
    { stamp: { ...stamp, bindingId: "041" } },
    { stamp: { ...stamp, bindingUpdatedAt: "2026-10-12T01:02:03Z" } },
    { stamp: { ...stamp, jobId: stamp.jobId.toUpperCase() } },
    { stamp, jobId: stamp.jobId },
  ])
    expect((await call("status", args)).body).toEqual({
      error: "invalid_input",
    });
  // 상태 파일이 없거나 24시간 정리 뒤에도 not_found 일 뿐 worker 를 띄우지 않는다.
  expect(
    (await call("status", { stamp: { ...stamp, jobId: randomUUID() } })).body,
  ).toEqual({ error: "not_found" });
});

test("private status 는 묵은 job 을 기존 규칙으로 끝내고 허용한 칸만 돌려준다", async () => {
  const { jobDir, snapshot, call } = await setup();
  const old = new Date(Date.now() - 20 * 60_000).toISOString();
  for (const [clicked, expected] of [
    [false, "failed"],
    [true, "unknown"],
  ] as const) {
    const stamp = stampFor(snapshot);
    await createState(
      jobDir,
      runningState(stamp.jobId, {
        snapshot_stamp: stamp,
        started_at: old,
        heartbeat_at: old,
        stage: "save",
        save_clicked: clicked,
      }),
    );
    const body = (await call("status", { stamp })).body;
    expect(body).toMatchObject({
      status: expected,
      save_clicked: clicked,
      error_code: "timeout",
      stage: "save",
    });
    expect(Object.keys(body).sort()).toEqual([
      "error_code",
      "finished_at",
      "job_id",
      "save_clicked",
      "saved_after",
      "saved_before",
      "stage",
      "stamp",
      "status",
    ]);
  }
  const stamp = stampFor(snapshot);
  await createState(
    jobDir,
    runningState(stamp.jobId, {
      snapshot_stamp: stamp,
      status: "failed",
      stage: "save_clicking",
      finished_at: new Date().toISOString(),
      result: { saved_before: 1, raw: "https://example.invalid/raw" },
      error: {
        code: "Bad Code",
        message: BODY_MARKER,
        focus_diagnostics: { url: "x" },
      },
    }),
  );
  expect((await call("status", { stamp })).body).toMatchObject({
    stage: null,
    saved_before: null,
    saved_after: null,
    error_code: null,
  });
});

test("일반 도구 목록·env·모델 인자로 private start·status 를 켤 수 없다", async () => {
  expect(() =>
    snapshotOperation(["--content-snapshot-operation", "start"], COMPILE_ONLY),
  ).toThrow("CONTENT_SNAPSHOT_UNAVAILABLE");
  expect(() =>
    snapshotOperation(["--content-snapshot-operation", "status"], COMPILE_ONLY),
  ).toThrow("CONTENT_SNAPSHOT_UNAVAILABLE");
  const enabled = { compile: true, start: true, status: true };
  expect(
    snapshotOperation(["--content-snapshot-operation", "start"], enabled),
  ).toBe("start");
  expect(() =>
    snapshotOperation(["--content-snapshot-operation", "publish"], enabled),
  ).toThrow();

  const { env, snapshot } = await setup();
  env.FOS_CONTENT_SNAPSHOT_OPERATION = "start";
  env.FOS_CONTENT_SAVE_TICKET = TICKET;
  const client = new Client({ name: "public", version: "1" });
  const server = createServer(env);
  const [left, right] = InMemoryTransport.createLinkedPair();
  await server.connect(right);
  await client.connect(left);
  try {
    const names = (await client.listTools()).tools.map((tool) => tool.name);
    for (const name of [
      "compile_content_snapshot",
      "start_content_snapshot",
      "content_snapshot_status",
    ])
      expect(names).not.toContain(name);
    const stamp = stampFor(snapshot);
    const result = await client.callTool({
      name: "start_content_snapshot",
      arguments: startArgs(snapshot, stamp),
    });
    expect(result.isError).toBe(true);
  } finally {
    await client.close();
    await server.close();
  }
  for (const operation of ["start", "status"] as const) {
    const privateServer = createSnapshotServer(env, operation);
    const privateClient = new Client({ name: "private", version: "1" });
    const [a, b] = InMemoryTransport.createLinkedPair();
    await privateServer.connect(b);
    await privateClient.connect(a);
    const tools = (await privateClient.listTools()).tools;
    expect(tools.map((tool) => tool.name)).toEqual([
      operation === "start"
        ? "start_content_snapshot"
        : "content_snapshot_status",
    ]);
    expect(tools[0]!.annotations?.readOnlyHint).toBe(operation === "status");
    await privateClient.close();
    await privateServer.close();
  }
});
