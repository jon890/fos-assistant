import { createHash, randomUUID } from "node:crypto";
import {
  copyFile,
  lstat,
  mkdir,
  mkdtemp,
  readFile,
  rm,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";

const connector = resolve(import.meta.dir, "..");
const repo = resolve(connector, "../../..");
const entry = "tests/content-snapshot-loopback-entry.ts";

/** stdout은 JVM과의 JSON protocol만 쓴다. 빌드와 설치 출력은 stderr다. */
async function run(command: string[], input = false) {
  const child = Bun.spawn(command, {
    cwd: connector,
    stdin: input ? "inherit" : "ignore",
    stdout: input ? "inherit" : 2,
    stderr: "inherit",
  });
  const terminate = () => child.kill("SIGTERM");
  process.on("SIGTERM", terminate);
  process.on("SIGINT", terminate);
  try {
    return await child.exited;
  } finally {
    process.off("SIGTERM", terminate);
    process.off("SIGINT", terminate);
  }
}

async function main() {
  const temporary = await mkdtemp(
    join(tmpdir(), "content-snapshot-p04b-loopback-"),
  );
  const container = `fos-synthetic-loopback-${randomUUID()}`;
  try {
    const context = join(temporary, "build"),
      snapshot = join(temporary, "snapshot");
    await mkdir(context);
    const dockerfile = join(connector, "scripts/test.Dockerfile");
    const source = await readFile(dockerfile);
    await writeFile(
      join(context, "Dockerfile"),
      Buffer.concat([
        source,
        Buffer.from(
          "\nCOPY package.json bun.lock /synthetic-deps/\nRUN cd /synthetic-deps && bun --no-env-file install --frozen-lockfile --network-concurrency=1\n",
        ),
      ]),
    );
    const hashInput = createHash("sha256")
      .update(source)
      .update("loopback-deps-v1");
    for (const file of ["package.json", "bun.lock"]) {
      const contents = await readFile(join(connector, file));
      hashInput.update(contents);
      await writeFile(join(context, file), contents);
    }
    const image = `fos-synthetic-loopback-test:${hashInput.digest("hex").slice(0, 16)}`;
    if ((await run(["docker", "build", "--tag", image, context])) !== 0)
      throw new Error("합성 image 빌드 실패");
    const listing = Bun.spawn(
      ["git", "ls-files", "-z", "--", "hermes/connectors/naver-blog"],
      { cwd: repo, stdout: "pipe" },
    );
    const files = (await new Response(listing.stdout).text())
      .split("\0")
      .filter(Boolean);
    if ((await listing.exited) !== 0) throw new Error("추적 파일 조회 실패");
    for (const file of files) {
      if (file.endsWith("/.env") || file.includes("/node_modules/"))
        throw new Error("환경 파일 거절");
      const source = join(repo, file),
        target = join(snapshot, file);
      if (!(await lstat(source)).isFile())
        throw new Error("일반 추적 파일만 허용");
      await mkdir(dirname(target), { recursive: true });
      await copyFile(source, target);
    }
    const archive = join(temporary, "fixture.tar");
    const archiveFlags =
      process.platform === "darwin"
        ? ["--no-xattrs", "--disable-copyfile"]
        : [];
    if (
      (await run([
        "tar",
        ...archiveFlags,
        "-cf",
        archive,
        "-C",
        snapshot,
        ".",
      ])) !== 0
    )
      throw new Error("합성 archive 실패");
    const hostFlags =
      process.platform === "darwin"
        ? []
        : ["--add-host", "host.docker.internal:host-gateway"];
    if (
      (await run([
        "docker",
        "create",
        "--name",
        container,
        ...hostFlags,
        "--cpus=2",
        "--memory=2g",
        "--pids-limit=256",
        "--shm-size=256m",
        "--entrypoint",
        "sh",
        image,
        "-c",
        "sleep infinity",
      ])) !== 0
    )
      throw new Error("합성 container 생성 실패");
    if (
      (await run(["docker", "cp", archive, `${container}:/fixture.tar`])) !== 0
    )
      throw new Error("합성 파일 전달 실패");
    if ((await run(["docker", "start", container])) !== 0)
      throw new Error("합성 container 시작 실패");
    return await run(
      [
        "docker",
        "exec",
        "--interactive",
        container,
        "sh",
        "-c",
        "mkdir /work && tar -xf /fixture.tar -C /work && cd /work/hermes/connectors/naver-blog && cp -a /synthetic-deps/node_modules . && exec bun --no-env-file tests/content-snapshot-loopback-entry.ts",
      ],
      true,
    );
  } finally {
    await run(["docker", "rm", "--force", container]);
    await rm(temporary, { recursive: true, force: true });
  }
}

try {
  process.exitCode = await main();
} catch {
  console.error("합성 loopback runner 실패");
  process.exitCode = 1;
}
