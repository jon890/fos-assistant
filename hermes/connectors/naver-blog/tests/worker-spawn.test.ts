import { expect, test } from "bun:test";
import { randomUUID } from "node:crypto";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createState, readState, stateFile, writeInput } from "../src/jobs.ts";
import { workerEnvironment } from "../src/worker-spawn.ts";

test("ticket 을 받은 worker 는 부모 cwd 의 .env 에 있는 whitelist 밖의 키를 읽지 않는다", async () => {
  const dir = await mkdtemp(join(tmpdir(), "snapshot-worker-dotenv-"));
  try {
    await writeFile(join(dir, ".env"), "FOS_TEST_DOTENV_SENTINEL=synthetic\n");
    const jobId = randomUUID();
    const jobFile = stateFile(dir, jobId);
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
      title: "합성",
      category: "일상",
      tags: [],
      body: "본문",
    });
    const env = {
      ...workerEnvironment(process.env),
      NAVER_BLOG_ID: "example-blog",
      NAVER_BLOG_JOB_DIR: dir,
      FOS_CONTENT_SAVE_TICKET: "synthetic-ticket",
    };
    const module = join(import.meta.dir, "../src/worker-spawn.ts");
    const entry = join(import.meta.dir, "fake-snapshot-worker-entry.ts");
    // 부모는 .env 를 읽지 않는다. 실제 spawnWorker 가 띄운 worker 에만 cwd 의 .env 를 노출한다.
    const parent = Bun.spawn(
      [
        process.execPath,
        "--no-env-file",
        "--eval",
        `import { spawnWorker } from ${JSON.stringify(module)}; spawnWorker(${JSON.stringify(entry)}, ${JSON.stringify(jobFile)}, ${JSON.stringify(env)});`,
      ],
      {
        cwd: dir,
        env: workerEnvironment(process.env),
        stdout: "ignore",
        stderr: "inherit",
      },
    );
    expect(await parent.exited).toBe(0);
    const deadline = Date.now() + 5_000;
    while (!(await readFile(`${jobFile}.env-after`, "utf8").catch(() => ""))) {
      if (Date.now() >= deadline)
        throw new Error("합성 worker 가 끝나지 않았다");
      await Bun.sleep(20);
    }
    expect(await readState(dir, jobId)).toMatchObject({ status: "succeeded" });
    const names = JSON.parse(await readFile(`${jobFile}.env`, "utf8"));
    expect(names).toContain("FOS_CONTENT_SAVE_TICKET");
    expect(names).not.toContain("FOS_TEST_DOTENV_SENTINEL");
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
