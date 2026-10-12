import { afterEach, expect, test } from "bun:test";
import { randomUUID } from "node:crypto";
import { mkdtemp, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { CompiledSnapshotV1 } from "../src/content-draft.ts";
import { compiledSnapshotV1Schema } from "../src/content-draft.ts";
import { type Fetcher, verifySnapshot } from "../src/content-snapshot-guard.ts";
import type { runDraft } from "../src/editor/run.ts";
import {
  acquireLock,
  createState,
  finishedFile,
  readState,
  stateFile,
  writeInput,
} from "../src/jobs.ts";
import type {
  SnapshotJobState,
  SnapshotStamp,
} from "../src/content-snapshot-job.ts";
import type { Env } from "../src/session.ts";
import { runWorker } from "../src/worker.ts";
import {
  sha256,
  stampFor,
  syntheticSnapshot,
} from "./content-snapshot-fixture.ts";

const URL = "http://127.0.0.1:9/base/internal/content-draft-jobs/authorize";
const cleanups: Array<() => unknown> = [];
afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

async function photoRoot() {
  const root = await realpath(
    await mkdtemp(join(tmpdir(), "snapshot-worker-photos-")),
  );
  cleanups.push(() => rm(root, { recursive: true, force: true }));
  return root;
}

test("verifySnapshot 은 다시 compile 한 결과와 정확히 같은 snapshot 만 받는다", async () => {
  const root = await photoRoot();
  const snapshot = await syntheticSnapshot(join(root, "p"), 3, [2, 1, 3]);
  const empty = await syntheticSnapshot(join(root, "q"), 1, []);
  expect(verifySnapshot(snapshot)).toEqual(snapshot);
  expect(verifySnapshot(empty)).toEqual(empty);
  const manifest = snapshot.assetManifest;
  const tampered: unknown[] = [
    {
      ...snapshot,
      payload: { ...snapshot.payload, body: `${snapshot.payload.body} ` },
    },
    { ...snapshot, payload: { ...snapshot.payload, title: "다른 제목" } },
    { ...snapshot, snapshotHash: "0".repeat(64) },
    { ...snapshot, payloadFingerprint: "0".repeat(64) },
    {
      ...snapshot,
      assetManifest: [{ ...manifest[0]!, position: 5 }, ...manifest.slice(1)],
    },
    {
      ...snapshot,
      assetManifest: [
        manifest[0]!,
        { ...manifest[1]!, sourceFingerprint: "c".repeat(64) },
        manifest[2]!,
      ],
    },
    { ...snapshot, assetManifest: manifest.slice(0, 2) },
    { ...snapshot, photoNotes: { "1": "첫 자리에만 붙인 설명" } },
    { ...snapshot, canonicalBlocks: snapshot.canonicalBlocks.slice(0, -1) },
    { ...empty, payload: { ...empty.payload, photo_dir: "/somewhere" } },
    { ...snapshot, extra: true },
    null,
  ];
  for (const value of tampered) expect(verifySnapshot(value)).toBeNull();
});

test("compiler 는 반복 사진을 CONTENT_DRAFT_DUPLICATE_PHOTO 로 조기에 거절한다", async () => {
  await expect(
    syntheticSnapshot(join(await photoRoot(), "p"), 2, [1, 2, 1]),
  ).rejects.toMatchObject({ code: "CONTENT_DRAFT_DUPLICATE_PHOTO" });
});

/** 옛 compiler 방식으로 두 지문을 다시 만든다. 유효한 compiler 결과와 먼저 대조한다. */
function recompute(snapshot: CompiledSnapshotV1) {
  const computed = structuredClone(snapshot);
  const payload = snapshot.payload;
  computed.payloadFingerprint = sha256(
    JSON.stringify([
      payload.title,
      payload.category,
      payload.tags,
      payload.body,
      payload.photo_dir ?? null,
    ]),
  );
  computed.snapshotHash = sha256(
    JSON.stringify([
      1,
      snapshot.compilerVersion,
      snapshot.draftId,
      snapshot.revision,
      snapshot.canonicalBlocks,
      snapshot.assetManifest.map((entry) => [
        entry.position,
        entry.assetId,
        entry.sourceFingerprint,
        entry.fileName,
      ]),
      computed.payloadFingerprint,
      Object.entries(snapshot.photoNotes).map(([number, note]) => [
        Number(number),
        note,
      ]),
    ]),
  );
  return computed;
}

test("compiler 를 거치지 않은 반복 snapshot 은 지문이 맞아도 worker 재검증에서 거절한다", async () => {
  const original = await syntheticSnapshot(join(await photoRoot(), "p"), 2);
  expect(recompute(original)).toEqual(original);
  const repeated = structuredClone(original);
  repeated.canonicalBlocks.push(repeated.canonicalBlocks[1]!);
  repeated.assetManifest.push({ ...repeated.assetManifest[0]!, position: 3 });
  repeated.payload.body += "\n[사진 3: 1.jpg]";
  const snapshot = recompute(repeated);
  // 옛 compiler 와 같은 지문을 갖췄으므로 형식 오류나 hash 불일치만으로는 거절할 수 없다.
  expect(compiledSnapshotV1Schema.safeParse(snapshot).success).toBe(true);
  expect(verifySnapshot(snapshot)).toBeNull();
  const { dir, jobId } = await snapshotJob(snapshot, stampFor(snapshot));
  const { events, fetcher } = recorder();
  let opened = false;
  await runWorker(stateFile(dir, jobId), {
    env: workerEnv(),
    fetch: fetcher,
    runDraft: async () => {
      opened = true;
      throw new Error("열면 안 된다");
    },
    limitMs: 5_000,
  });
  expect(await readState(dir, jobId)).toMatchObject({
    status: "failed",
    save_clicked: false,
    error: { code: "snapshot_invalid" },
  });
  expect(events).toEqual([]);
  expect(opened).toBe(false);
});

/** 예약 stamp 가 있는 상태와 snapshot 입력을 준비한 worker 하나. */
async function snapshotJob(
  snapshot: CompiledSnapshotV1,
  stamp: SnapshotStamp | null,
) {
  const dir = await mkdtemp(join(tmpdir(), "snapshot-worker-jobs-"));
  cleanups.push(() => rm(dir, { recursive: true, force: true }));
  const jobId = stamp?.jobId ?? randomUUID();
  await acquireLock(dir, "example-blog", jobId);
  const state: SnapshotJobState = {
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
    ...(stamp ? { snapshot_stamp: stamp } : {}),
  };
  await createState(dir, state);
  await writeInput(dir, jobId, { kind: "content_snapshot", snapshot });
  return { dir, jobId };
}

function recorder(decide: (stage: string) => boolean = () => true) {
  const events: string[] = [];
  const fetcher: Fetcher = async (_url, init) => {
    const body = JSON.parse(String(init.body)) as {
      stage: string;
      position?: number;
    };
    events.push(
      `authorize:${body.stage}${body.position === undefined ? "" : `:${body.position}`}`,
    );
    return decide(body.stage)
      ? Response.json({ allowed: true })
      : new Response("", { status: 403 });
  };
  return { events, fetcher };
}

/** 저장 단계까지 가서 save_clicking 이 끝나면 클릭으로 세는 대역. */
function clickingRun(
  events: string[],
  dir: string,
  jobId: string,
  beforeSave?: () => Promise<void>,
): typeof runDraft {
  return async (_env, _blocks, _input, onStage, _signal, _options, guard) => {
    await onStage("open");
    for (const entry of guard?.manifest ?? [])
      await guard!.authorizePhoto(entry.position);
    await onStage("save");
    await beforeSave?.();
    await onStage("save_clicking");
    const state = await readState(dir, jobId);
    events.push(`click:save_clicked=${state?.save_clicked}`);
    return { state: null, savedBefore: 1, savedAfter: 2 };
  };
}

const workerEnv = (extra: Env = {}): Env => ({
  NAVER_BLOG_BROWSER_URL: "http://127.0.0.1:9/synthetic",
  NAVER_BLOG_ID: "example-blog",
  FOS_CONTENT_SAVE_TICKET: "synthetic-ticket",
  FOS_CONTENT_SAVE_AUTHORIZE_URL: URL,
  ...extra,
});

test("save claim 은 save_clicked 기록 전에 받고, 거절되면 클릭 0회와 save_clicked=false 다", async () => {
  const snapshot = await syntheticSnapshot(join(await photoRoot(), "p"), 1);
  for (const allowSave of [true, false]) {
    const stamp = stampFor(snapshot);
    const { dir, jobId } = await snapshotJob(snapshot, stamp);
    const { events, fetcher } = recorder(
      (stage) => stage !== "save" || allowSave,
    );
    const env = workerEnv();
    await runWorker(stateFile(dir, jobId), {
      env,
      fetch: fetcher,
      runDraft: clickingRun(events, dir, jobId),
      limitMs: 5_000,
    });
    const state = await readState(dir, jobId);
    if (allowSave) {
      expect(events).toEqual([
        "authorize:start",
        "authorize:photo:1",
        "authorize:save",
        "click:save_clicked=true",
      ]);
      expect(state).toMatchObject({ status: "succeeded", save_clicked: true });
    } else {
      expect(events).toEqual([
        "authorize:start",
        "authorize:photo:1",
        "authorize:save",
      ]);
      expect(state).toMatchObject({
        status: "failed",
        save_clicked: false,
        error: { code: "authorize_denied", stage: "save" },
      });
    }
    // ticket 과 주소는 worker 가 시작하자마자 env 에서 지운다.
    expect(env.FOS_CONTENT_SAVE_TICKET).toBeUndefined();
    expect(env.FOS_CONTENT_SAVE_AUTHORIZE_URL).toBeUndefined();
  }
});

test("save claim 을 받은 뒤 정리가 job 을 끝냈으면 기존 finishClaimed 검사로 누르지 않는다", async () => {
  const snapshot = await syntheticSnapshot(join(await photoRoot(), "p"), 1);
  const stamp = stampFor(snapshot);
  const { dir, jobId } = await snapshotJob(snapshot, stamp);
  const { events, fetcher } = recorder();
  const claimAfterAuthorize: Fetcher = async (url, init) => {
    const response = await fetcher(url, init);
    if (JSON.parse(String(init.body)).stage === "save")
      await writeFile(finishedFile(dir, jobId), "");
    return response;
  };
  await runWorker(stateFile(dir, jobId), {
    env: workerEnv(),
    fetch: claimAfterAuthorize,
    runDraft: clickingRun(events, dir, jobId),
    limitMs: 5_000,
  });
  expect(events.at(-1)).toBe("authorize:save");
  expect(events.some((event) => event.startsWith("click"))).toBe(false);
});

test("stamp 없는 상태·ticket 없는 env·다른 hash 의 snapshot job 은 CP 호출과 브라우저 없이 snapshot_invalid 다", async () => {
  const snapshot = await syntheticSnapshot(join(await photoRoot(), "p"), 1);
  const other = await syntheticSnapshot(join(await photoRoot(), "o"), 2);
  const cases: Array<[SnapshotStamp | null, Env]> = [
    [null, workerEnv()],
    [stampFor(snapshot), workerEnv({ FOS_CONTENT_SAVE_TICKET: "" })],
    [
      stampFor(snapshot),
      workerEnv({
        FOS_CONTENT_SAVE_AUTHORIZE_URL: "http://127.0.0.1:9/elsewhere",
      }),
    ],
    [stampFor(other), workerEnv()],
  ];
  for (const [stamp, env] of cases) {
    const { dir, jobId } = await snapshotJob(snapshot, stamp);
    const { events, fetcher } = recorder();
    let opened = false;
    await runWorker(stateFile(dir, jobId), {
      env,
      fetch: fetcher,
      runDraft: async () => {
        opened = true;
        throw new Error("열면 안 된다");
      },
      limitMs: 5_000,
    });
    expect(await readState(dir, jobId)).toMatchObject({
      status: "failed",
      error: { code: "snapshot_invalid" },
    });
    expect(events).toEqual([]);
    expect(opened).toBe(false);
  }
});

test("기존 DSL job 도 env 의 ticket 이름을 지우고 runDraft 에 guard 를 넘기지 않는다", async () => {
  const dir = await mkdtemp(join(tmpdir(), "snapshot-worker-legacy-"));
  cleanups.push(() => rm(dir, { recursive: true, force: true }));
  const jobId = randomUUID();
  await acquireLock(dir, "example-blog", jobId);
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
  await writeInput(dir, jobId, {
    title: "가상",
    category: "가상국수로그",
    tags: [],
    body: "본문",
  });
  const env = workerEnv();
  const { events, fetcher } = recorder();
  let received: unknown = "unset";
  await runWorker(stateFile(dir, jobId), {
    env,
    fetch: fetcher,
    runDraft: async (
      _env,
      _blocks,
      _input,
      onStage,
      _signal,
      _options,
      guard,
    ) => {
      received = guard;
      await onStage("save_clicking");
      return { state: null, savedBefore: 1, savedAfter: 2 };
    },
    limitMs: 5_000,
  });
  expect(received).toBeUndefined();
  expect(events).toEqual([]);
  expect(env.FOS_CONTENT_SAVE_TICKET).toBeUndefined();
  expect(await readState(dir, jobId)).toMatchObject({
    status: "succeeded",
    save_clicked: true,
  });
});
