import { createHash } from "node:crypto";
import {
  appendFile,
  mkdir,
  mkdtemp,
  readFile,
  realpath,
  rm,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { createInterface } from "node:readline";
import { runWorker } from "../src/worker.ts";
import { runDraft } from "../src/editor/run.ts";
import {
  startSnapshotJob,
  snapshotJobStatus,
  type SnapshotStamp,
} from "../src/content-snapshot-job.ts";
import { readState, stateFile } from "../src/jobs.ts";
import { EDITOR_METHODS, FakeCdp, FakeEditor } from "./fake-cdp.ts";

const workerIndex = process.argv.indexOf("--worker");
const WORKER_LOG = "workers.log";
if (workerIndex >= 0) {
  const file = process.argv[workerIndex + 1]!;
  const directory = dirname(file);
  // 어떤 경로로 떴든 worker 실행을 worker 쪽에서 센다. 시작 명령이 읽은 pid 만으로는 두 번째 실행이 보이지 않는다.
  await appendFile(join(directory, WORKER_LOG), `${process.pid}\n`);
  const scenario = JSON.parse(
    await readFile(join(directory, "fixture.json"), "utf8"),
  ).scenario;
  await runWorker(file, {
    limitMs: 20_000,
    runDraft: async (env, blocks, input, onStage, signal, _options, guard) => {
      const result = await runDraft(
        env,
        blocks,
        input,
        async (stage) => {
          if (stage === "save" && scenario === "wait_save") {
            await writeFile(join(directory, "waiting"), "");
            while (!(await Bun.file(join(directory, "continue")).exists())) {
              if (signal.aborted) throw new Error("fixture aborted");
              await Bun.sleep(20);
            }
          }
          await onStage(stage);
          if (stage === "save_clicking" && scenario === "before_click")
            throw new Error("fixture stopped");
        },
        signal,
        { openSeconds: 2, stepSeconds: 1, photoSeconds: 2, saveSeconds: 1 },
        guard,
      );
      if (scenario === "after_click") throw new Error("fixture stopped");
      return result;
    },
  });
} else {
  const root = await realpath(
    await mkdtemp(join(tmpdir(), "content-loopback-")),
  );
  const cdp = new FakeCdp({
    allowed: [...EDITOR_METHODS, "Storage.getCookies"],
  });
  const editor = new FakeEditor();
  editor.install(cdp);
  cdp.on("Storage.getCookies", () => ({
    cookies: [
      { name: "NID_AUT", domain: ".naver.com" },
      { name: "NID_SES", domain: ".naver.com" },
    ],
  }));
  const env = {
    NAVER_BLOG_BROWSER_URL: cdp.url,
    NAVER_BLOG_ID: "example-blog",
    NAVER_BLOG_JOB_DIR: root,
    PATH: process.env.PATH,
  };
  let stamp!: SnapshotStamp;
  const pids = new Set<number>();
  const photoDirs = new Set<string>();
  const answer = (value: unknown) =>
    process.stdout.write(JSON.stringify(value) + "\n");
  const wait = async (predicate: () => Promise<boolean>) => {
    const deadline = Date.now() + 25_000;
    while (!(await predicate())) {
      if (Date.now() >= deadline) throw new Error("fixture timeout");
      await Bun.sleep(20);
    }
  };
  answer({ ready: true });
  const lines = createInterface({ input: process.stdin });
  try {
    for await (const line of lines) {
      const command = JSON.parse(line);
      try {
        if (command.operation === "start") {
          stamp = command.args.stamp;
          const snapshot = command.args.snapshot;
          if (snapshot.payload.photo_dir)
            photoDirs.add(snapshot.payload.photo_dir);
          editor.categories = [snapshot.payload.category];
          for (const photo of command.photos ?? []) {
            const dir = snapshot.payload.photo_dir;
            await mkdir(dir, { recursive: true });
            await writeFile(
              join(dir, photo.fileName),
              Buffer.from(photo.bytes, "base64"),
            );
          }
          if (command.scenario === "replace_photo") {
            await writeFile(
              join(
                snapshot.payload.photo_dir,
                snapshot.assetManifest[0].fileName,
              ),
              new Uint8Array([255, 216, 255, 224, 99]),
            );
          }
          await writeFile(
            join(root, "fixture.json"),
            JSON.stringify({ scenario: command.scenario }),
          );
          const result = await startSnapshotJob(
            {
              ...env,
              NAVER_BLOG_ATTACHMENT_DIR: snapshot.payload.photo_dir,
              FOS_CONTENT_SAVE_AUTHORIZE_URL: command.authorizeUrl,
            },
            command.args,
            {
              workerEntry: import.meta.filename,
              startWaitMs: 5000,
              checkSession: async () => ({ logged_in: true }) as any,
            },
          );
          const state = await readState(root, stamp.jobId);
          if (state?.pid) pids.add(state.pid);
          answer(result);
        } else if (command.operation === "wait_save") {
          await wait(() => Bun.file(join(root, "waiting")).exists());
          answer({ waiting: true });
        } else if (command.operation === "continue") {
          await writeFile(join(root, "continue"), "");
          answer({ continued: true });
        } else {
          if (command.operation === "terminal") {
            await wait(
              async () =>
                (await readState(root, stamp.jobId))?.status !== "running",
            );
          }
          const status = await snapshotJobStatus(env, {
            stamp: command.stamp ?? stamp,
          });
          const uploads = cdp.calls
            .filter((call) => call.method === "Runtime.callFunctionOn")
            .map((call) =>
              createHash("sha256")
                .update(Buffer.from(call.params.arguments[0].value, "base64"))
                .digest("hex"),
            );
          answer({
            status,
            workersStarted: (await workerRuns(root)).length,
            clicks: editor.saveClicks,
            uploads,
            violations: cdp.violations,
          });
        }
      } catch {
        answer({ error: "fixture_unavailable" });
      }
    }
  } finally {
    for (const pid of [...pids, ...(await workerRuns(root))]) {
      try {
        process.kill(pid, "SIGTERM");
      } catch {}
    }
    cdp.stop();
    for (const dir of photoDirs)
      await rm(dir, { recursive: true, force: true });
    await rm(root, { recursive: true, force: true });
  }
}

/** worker 가 시작할 때 남긴 pid 목록이다. 아직 뜬 worker 가 없으면 비어 있다. */
async function workerRuns(root: string) {
  const text = await readFile(join(root, WORKER_LOG), "utf8").catch(() => "");
  return text.split("\n").filter(Boolean).map(Number);
}
