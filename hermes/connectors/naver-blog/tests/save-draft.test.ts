import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { afterEach, expect, test } from "bun:test";
import { mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { inputFile, readState, stateFile } from "../src/jobs.ts";
import { createServer, type ServerDeps } from "../src/server.ts";
import type { Env } from "../src/session.ts";
import { FakeCdp, upstreamText } from "./fake-cdp.ts";

const SERVER_ENTRY = join(import.meta.dir, "../src/server.ts");
const JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4]);
const LOGGED_IN = [
  { name: "NID_AUT", domain: ".naver.com" },
  { name: "NID_SES", domain: ".naver.com" },
];
const BODY = "가상국수에 다녀왔어요\n[사진 1: 101.jpg]";
const cleanups: Array<() => unknown> = [];

afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

/** 가짜 브라우저와 사진 디렉터리, 작업 디렉터리를 준비하고 MCP 서버의 도구를 부르는 함수를 돌려준다. */
async function setup({
  cookies = LOGGED_IN,
  photo = JPEG,
  deps = { workerEntry: SERVER_ENTRY },
}: {
  cookies?: typeof LOGGED_IN;
  photo?: Uint8Array;
  deps?: Partial<ServerDeps>;
} = {}) {
  const cdp = new FakeCdp();
  cdp.on("Storage.getCookies", () => ({ cookies }));
  cleanups.push(() => cdp.stop());
  const photoDir = await mkdtemp(join(tmpdir(), "naver-blog-photos-"));
  const jobDir = await mkdtemp(join(tmpdir(), "naver-blog-jobs-"));
  cleanups.push(() => rm(photoDir, { recursive: true, force: true }));
  cleanups.push(() => rm(jobDir, { recursive: true, force: true }));
  await writeFile(join(photoDir, "101.jpg"), photo);
  const env: Env = {
    NAVER_BLOG_CDP_URL: cdp.url,
    NAVER_BLOG_ID: "example-blog",
    NAVER_BLOG_JOB_DIR: jobDir,
    NAVER_BLOG_TEST_FAKE_RUN: "1",
    PATH: process.env.PATH,
    HOME: process.env.HOME,
  };
  const client = new Client({ name: "naver-blog-test", version: "1.0.0" });
  const server = createServer(env, deps);
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await server.connect(serverTransport);
  await client.connect(clientTransport);
  cleanups.push(() => server.close());
  cleanups.push(() => client.close());

  /** 도구를 부르고, 결과 글에 CDP 주소와 사진 디렉터리, 브라우저 원문이 없는지 함께 본다. */
  const call = async (name: string, args: Record<string, unknown>) => {
    const result = await client.callTool({ name, arguments: args });
    const text = (result.content as Array<{ text: string }>)[0]?.text ?? "";
    expect(text).not.toContain(cdp.url);
    expect(text).not.toContain(new URL(cdp.url).host);
    expect(text).not.toContain(photoDir);
    expect(text).not.toContain(upstreamText);
    return { isError: result.isError === true, body: JSON.parse(text) };
  };
  const draft = {
    title: "가상국수 다녀온 날",
    category: "가상국수로그",
    tags: ["점심"],
    body: BODY,
    photo_dir: photoDir,
  };
  return { cdp, jobDir, photoDir, call, draft };
}

/** 그 프로세스의 프로세스 묶음 번호. */
async function pgid(pid: number) {
  const ps = Bun.spawn(["ps", "-o", "pgid=", "-p", String(pid)], { stdout: "pipe" });
  const text = await new Response(ps.stdout).text();
  await ps.exited;
  return text.trim();
}

test("save_draft 는 다른 프로세스 묶음의 작업 프로세스를 띄우고 draft_job 이 succeeded 를 돌려준다", async () => {
  const { jobDir, call, draft } = await setup();

  const started = await call("save_draft", draft);

  expect(started.isError).toBe(false);
  expect(started.body.status).toBe("running");
  expect(started.body.job_id).toMatch(/^[0-9a-f-]{36}$/);
  const state = await readState(jobDir, started.body.job_id);
  expect(state?.pid).toBeNumber();
  const workerGroup = await pgid(state!.pid!);
  expect(workerGroup).not.toBe("");
  expect(workerGroup).not.toBe(await pgid(process.pid));

  const job = await call("draft_job", { job_id: started.body.job_id, wait_seconds: 4 });

  expect(job.isError).toBe(false);
  expect(job.body).toMatchObject({
    job_id: started.body.job_id,
    status: "succeeded",
    save_clicked: false,
    error: null,
    result: { saved_before: 0, saved_after: 1 },
  });
  expect(job.body).not.toContainKey("pid");
  expect(job.body).not.toContainKey("heartbeat_at");
  expect((await readdir(jobDir)).sort()).toEqual([
    `${started.body.job_id}.finished`,
    `${started.body.job_id}.json`,
  ]);
});

test("로그인 쿠키가 없으면 작업을 만들지 않고 NAVER_BLOG_LOGIN_REQUIRED 다", async () => {
  const { jobDir, call, draft } = await setup({ cookies: [] });

  const result = await call("save_draft", draft);

  expect(result).toEqual({ isError: true, body: { error: { code: "NAVER_BLOG_LOGIN_REQUIRED" } } });
  expect(await readdir(jobDir)).toEqual([]);
});

test("서명이 확장자와 맞지 않는 사진이면 브라우저에 닿기 전에 NAVER_BLOG_PHOTO_INVALID 다", async () => {
  const { cdp, jobDir, call, draft } = await setup({ photo: new TextEncoder().encode("not an image") });

  const result = await call("save_draft", draft);

  expect(result).toEqual({ isError: true, body: { error: { code: "NAVER_BLOG_PHOTO_INVALID" } } });
  expect(cdp.httpRequests).toEqual([]);
  expect(await readdir(jobDir)).toEqual([]);
});

test("초안이 계약을 어기면 문장 없이 NAVER_BLOG_INVALID_INPUT 이다", async () => {
  const { jobDir, call, draft } = await setup();

  const result = await call("save_draft", { ...draft, title: "" });

  expect(result).toEqual({ isError: true, body: { error: { code: "NAVER_BLOG_INVALID_INPUT" } } });
  expect(await readdir(jobDir)).toEqual([]);
});

test("작업 프로세스가 시작을 알리지 않으면 NAVER_BLOG_START_UNKNOWN 이고 작업 파일에 연결 값과 본문이 섞이지 않는다", async () => {
  const silentDir = await mkdtemp(join(tmpdir(), "naver-blog-entry-"));
  cleanups.push(() => rm(silentDir, { recursive: true, force: true }));
  const silentEntry = join(silentDir, "silent.ts");
  await writeFile(silentEntry, "");
  const { cdp, jobDir, photoDir, call, draft } = await setup({
    deps: { workerEntry: silentEntry, startWaitMs: 300 },
  });

  const result = await call("save_draft", draft);

  expect(result).toEqual({ isError: true, body: { error: { code: "NAVER_BLOG_START_UNKNOWN" } } });
  const names = await readdir(jobDir);
  const jobId = names.find((name) => /^[0-9a-f-]{36}\.json$/.test(name))!.slice(0, 36);
  const stateText = await readFile(stateFile(jobDir, jobId), "utf8");
  const inputText = await readFile(inputFile(jobDir, jobId), "utf8");
  expect(JSON.parse(stateText)).toMatchObject({ status: "running", stage: "queued", pid: null });
  expect(stateText).not.toContain(photoDir);
  expect(stateText).not.toContain("가상국수에 다녀왔어요");
  expect(JSON.parse(inputText)).toEqual(draft);
  expect(inputText).not.toContain(cdp.url);
  expect(inputText).not.toContain(new URL(cdp.url).host);

  // 시작을 알리지 않은 작업의 잠금은 만든 지 10초 안이라 둘째 요청을 막는다.
  const second = await call("save_draft", draft);
  expect(second).toEqual({ isError: true, body: { error: { code: "NAVER_BLOG_BUSY" } } });
});

test("없는 작업 번호면 NAVER_BLOG_JOB_NOT_FOUND 다", async () => {
  const { call } = await setup();

  const result = await call("draft_job", {
    job_id: "00000000-0000-4000-8000-000000000000",
    wait_seconds: 0,
  });

  expect(result).toEqual({ isError: true, body: { error: { code: "NAVER_BLOG_JOB_NOT_FOUND" } } });
});

test("draft_job 은 pid 가 사라진 running 작업을 timeout 으로 끝내고 잠금을 푼다", async () => {
  const { jobDir, call } = await setup({ deps: { workerEntry: SERVER_ENTRY } });
  const jobId = "11111111-1111-4111-8111-111111111111";
  const gone = Bun.spawn([process.execPath, "-e", ""]);
  await gone.exited;
  await writeFile(
    stateFile(jobDir, jobId),
    JSON.stringify({
      job_id: jobId,
      status: "running",
      stage: "fill",
      save_clicked: false,
      started_at: new Date().toISOString(),
      finished_at: null,
      result: null,
      error: null,
      pid: gone.pid,
      heartbeat_at: new Date().toISOString(),
    }),
  );
  await writeFile(
    join(jobDir, "lock-0123456789abcdef"),
    JSON.stringify({ job_id: jobId, created_at: new Date().toISOString() }),
  );

  const result = await call("draft_job", { job_id: jobId, wait_seconds: 0 });

  expect(result.body).toMatchObject({ status: "failed", error: { code: "timeout", stage: "fill" } });
  expect((await readdir(jobDir)).sort()).toEqual([`${jobId}.finished`, `${jobId}.json`]);
});
