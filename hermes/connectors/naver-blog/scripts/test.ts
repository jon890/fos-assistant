import { copyFile, lstat, mkdir, mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { createHash, randomUUID } from "node:crypto";

const connector = resolve(import.meta.dir, "..");
const root = resolve(connector, "../../..");
const args = process.argv.slice(2);
const tests = args.length ? args : ["./src", "./tests", "./scripts"];

async function run(command: string[], cwd = connector, stdin?: Bun.BunFile) {
  const child = Bun.spawn(command, { cwd, stdin, stdout: "inherit", stderr: "inherit" });
  const forward = (signal: NodeJS.Signals) => child.kill(signal);
  const interrupt = () => forward("SIGINT"), terminate = () => forward("SIGTERM");
  process.on("SIGINT", interrupt);
  process.on("SIGTERM", terminate);
  try { return await child.exited; }
  finally { process.off("SIGINT", interrupt); process.off("SIGTERM", terminate); }
}

/** Linux CI는 설치된 Chrome을 쓰고, macOS는 같은 키를 실제 Linux Chrome으로 보낸다. */
async function main() {
  if (process.platform !== "darwin") return run([process.execPath, "--no-env-file", "test", ...tests]);
  const temporary = await mkdtemp(join(tmpdir(), "fos-connector-test-"));
  const container = `fos-synthetic-test-${randomUUID()}`;
  let started = false;
  try {
    const context = join(temporary, "build"), snapshot = join(temporary, "snapshot");
    await mkdir(context);
    const dockerfile = join(import.meta.dir, "test.Dockerfile");
    await copyFile(dockerfile, join(context, "Dockerfile"));
    const hash = createHash("sha256").update(await readFile(dockerfile)).digest("hex").slice(0, 16);
    const image = `fos-synthetic-chrome-test:${hash}`;
    const build = await run(["docker", "build", "--tag", image, context]);
    if (build !== 0) return build;

    // 공개하는 Git 추적 파일만 복사한다. 개인 파일과 실제 profile은 넣지 않는다.
    const listing = Bun.spawn(["git", "ls-files", "-z", "--", "hermes/connectors/naver-blog",
      "hermes/plugins/dashboard-profile-api/connector_schema.py"], { cwd: root, stdout: "pipe", stderr: "inherit" });
    const files = (await new Response(listing.stdout).text()).split("\0").filter(Boolean);
    if (await listing.exited !== 0) return 1;
    for (const file of files) {
      const source = join(root, file), target = join(snapshot, file);
      if (!(await lstat(source)).isFile()) throw new Error("합성 검사에는 일반 Git 추적 파일만 복사한다");
      if (file.endsWith("/.env") || file.includes("/node_modules/")) throw new Error("합성 검사에 환경 파일을 넣을 수 없다");
      await mkdir(dirname(target), { recursive: true });
      await copyFile(source, target);
    }
    // Docker VM이 macOS 임시 경로를 공유하지 않아도 합성 파일만 표준 입력으로 전달한다.
    const archive = join(temporary, "fixture.tar");
    const packed = await run(["tar", "--no-xattrs", "--disable-copyfile", "-cf", archive, "-C", snapshot, "."]);
    if (packed !== 0) return packed;
    const chrome = process.env.FOS_TEST_CHROME;
    console.info("macOS 합성 회귀: Linux Chrome · Bun 1.3.14 (Docker)");
    started = true;
    return await run(["docker", "run", "--interactive", "--rm", "--name", container, "--cpus=2", "--memory=2g",
      "--pids-limit=256", "--shm-size=256m",
      ...(chrome ? ["--env", `FOS_TEST_CHROME=${chrome}`] : []), image, "sh", "-c",
      'mkdir /work && tar -xf - -C /work && cd /work/hermes/connectors/naver-blog && bun --no-env-file install --frozen-lockfile --network-concurrency=1 && exec bun --no-env-file test "$@"',
      "synthetic-test", ...tests], connector, Bun.file(archive));
  } finally {
    // 이 실행이 만든 컨테이너만 정리한다. 정상 종료 때는 --rm이 이미 지웠다.
    try {
      if (started) {
        const cleanup = Bun.spawn(["docker", "rm", "--force", container], { stdout: "ignore", stderr: "ignore" });
        await cleanup.exited;
      }
    } finally { await rm(temporary, { recursive: true, force: true }); }
  }
}

try { process.exitCode = await main(); }
catch (error) { console.error(error); process.exitCode = 1; }
