import { afterEach, expect, test } from "bun:test";
import { execFileSync } from "node:child_process";
import {
  chmod,
  lstat,
  mkdir,
  mkdtemp,
  readFile,
  rename,
  rm,
  symlink,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { buildPackage, verifyPackage } from "./package.ts";

const temporary: string[] = [];
afterEach(async () => {
  for (const root of temporary.splice(0))
    await rm(root, { recursive: true, force: true });
});

// 작은 실제 Bun 서버를 빌드한다. manifest에서 정상 fixture를 역으로 만들지 않는다.
async function fixture() {
  const work = await mkdtemp(join(tmpdir(), "connector-package-test-"));
  temporary.push(work);
  const root = join(work, "source");
  const connector = join(root, "sample");
  const files = {
    "connector.json": '{"id":"sample"}\n',
    ".mcp.json":
      '{"mcpServers":{"sample":{"command":"bun","args":["${CLAUDE_PLUGIN_ROOT}/dist/sample-mcp.js"]}}}\n',
    ".claude-plugin/plugin.json": '{"name":"sample"}\n',
    "src/server.ts": 'console.log("sample ready");\n',
    "scripts/build.ts":
      'export async function buildBundle(outdir: string) { const result = await Bun.build({ entrypoints: [new URL("../src/server.ts", import.meta.url).pathname], target: "bun", format: "esm", minify: true, outdir, naming: "sample-mcp.js" }); if (!result.success) throw new Error("BUILD_FAILED"); }\n',
    "scripts/check.sh": '#!/bin/sh\nprintf "ready\\n"\n',
    "skills/sample/SKILL.md": "# Sample\n",
    "icon.svg": '<svg xmlns="http://www.w3.org/2000/svg"/>\n',
    "dist/keep.txt": "추적한 dist 부속 파일\n",
  };
  for (const [name, body] of Object.entries(files)) {
    await mkdir(join(connector, name, ".."), { recursive: true });
    await writeFile(join(connector, name), body);
  }
  await chmod(join(connector, "scripts/check.sh"), 0o755);
  await mkdir(join(connector, "node_modules"));
  function git(...args: string[]) {
    return execFileSync("git", ["-C", root, ...args], { encoding: "utf8" });
  }
  git("init", "--quiet");
  git("add", "sample");
  git(
    "-c",
    "user.name=Bundle test",
    "-c",
    "user.email=bundle-test@example.invalid",
    "commit",
    "--quiet",
    "-m",
    "fixture",
  );
  const commit = git("rev-parse", "HEAD").trim();
  const output = join(work, "bundle");
  return { work, root, connector, commit, output, git, files };
}

test("두 독립 빌드의 전체 바이트, 설치 파일과 git 실행 권한을 보존한다", async () => {
  const f = await fixture();
  await buildPackage(f.root, f.output, f.commit);
  const other = join(f.work, "other");
  await buildPackage(f.root, other, f.commit);
  await verifyPackage(f.root, f.output, f.commit);
  expect(await readFile(join(f.output, "manifest.json"), "utf8")).toBe(
    await readFile(join(other, "manifest.json"), "utf8"),
  );
  for (const [name, content] of Object.entries(f.files)) {
    expect(
      await readFile(join(f.output, "hermes/connectors/sample", name), "utf8"),
    ).toBe(content);
    expect(
      await readFile(join(other, "hermes/connectors/sample", name), "utf8"),
    ).toBe(content);
  }
  const entry = join(f.output, "hermes/connectors/sample/dist/sample-mcp.js");
  expect(
    (await readFile(entry)).equals(
      await readFile(
        join(other, "hermes/connectors/sample/dist/sample-mcp.js"),
      ),
    ),
  ).toBe(true);
  expect(execFileSync(process.execPath, [entry], { encoding: "utf8" })).toBe(
    "sample ready\n",
  );
  expect(
    (await lstat(join(f.output, "hermes/connectors/sample/scripts/check.sh")))
      .mode & 0o777,
  ).toBe(0o755);
  expect((await lstat(entry)).mode & 0o777).toBe(0o644);
});

const badBundles: [
  string,
  (f: Awaited<ReturnType<typeof fixture>>) => Promise<void>,
][] = [
  [
    "symlink",
    async (f) => {
      await symlink("manifest.json", join(f.output, "link"));
    },
  ],
  [
    "추가 파일",
    async (f) => {
      await writeFile(join(f.output, "extra"), "extra");
    },
  ],
  [
    "추가 빈 디렉터리",
    async (f) => {
      await mkdir(join(f.output, "extra"));
    },
  ],
  [
    "변조한 바이트",
    async (f) => {
      await writeFile(
        join(f.output, "hermes/connectors/sample/icon.svg"),
        "changed",
      );
    },
  ],
  [
    "누락한 entry",
    async (f) => {
      await rm(join(f.output, "hermes/connectors/sample/dist/sample-mcp.js"));
    },
  ],
  [
    "불필요한 실행 권한",
    async (f) => {
      await chmod(join(f.output, "hermes/connectors/sample/icon.svg"), 0o755);
    },
  ],
  [
    "사라진 실행 권한",
    async (f) => {
      await chmod(
        join(f.output, "hermes/connectors/sample/scripts/check.sh"),
        0o644,
      );
    },
  ],
  [
    "추가 쓰기 권한",
    async (f) => {
      await chmod(join(f.output, "hermes/connectors/sample/icon.svg"), 0o664);
    },
  ],
  [
    "디렉터리 권한",
    async (f) => {
      await chmod(join(f.output, "hermes/connectors/sample"), 0o777);
    },
  ],
];
for (const [name, mutate] of badBundles) {
  test(`verify 거절: ${name}`, async () => {
    const f = await fixture();
    await buildPackage(f.root, f.output, f.commit);
    await mutate(f);
    await expect(verifyPackage(f.root, f.output, f.commit)).rejects.toThrow();
  });
}

test("해시와 목록까지 다시 쓴 변조도 checkout의 바이트와 대조해 거절한다", async () => {
  const f = await fixture();
  await buildPackage(f.root, f.output, f.commit);
  const name = join(f.output, "manifest.json");
  const manifest = JSON.parse(await readFile(name, "utf8"));
  const bytes = "tampered";
  await writeFile(join(f.output, "hermes/connectors/sample/icon.svg"), bytes);
  manifest.connectors.sample.files["icon.svg"] = new Bun.CryptoHasher("sha256")
    .update(bytes)
    .digest("hex");
  await writeFile(name, JSON.stringify(manifest));
  await expect(verifyPackage(f.root, f.output, f.commit)).rejects.toThrow(
    "BUNDLE_SOURCE_MISMATCH",
  );
});

test("다른 SHA, path traversal, 다른 entry와 추가 connector를 거절한다", async () => {
  const f = await fixture();
  await buildPackage(f.root, f.output, f.commit);
  await expect(verifyPackage(f.root, f.output, "a".repeat(40))).rejects.toThrow(
    "BUNDLE_MANIFEST_MISMATCH",
  );
  const name = join(f.output, "manifest.json");
  const original = await readFile(name, "utf8");
  for (const mutate of [
    (m: any) => {
      m.connectors.sample.files["../../escape"] = "a".repeat(64);
    },
    (m: any) => {
      m.connectors.sample.entry = "../escape";
    },
    (m: any) => {
      m.connectors.extra = m.connectors.sample;
    },
    (m: any) => {
      m.connectors.sample.modes["scripts/check.sh"] = "0644";
    },
  ]) {
    const manifest = JSON.parse(original);
    mutate(manifest);
    await writeFile(name, JSON.stringify(manifest));
    await expect(verifyPackage(f.root, f.output, f.commit)).rejects.toThrow();
  }
});

test("잘못된 SHA와 mcp 실행 경로, 미설치 connector, source symlink를 거절한다", async () => {
  const f = await fixture();
  await expect(buildPackage(f.root, f.output, "bad")).rejects.toThrow(
    "INVALID_SOURCE_COMMIT",
  );
  await writeFile(
    join(f.connector, ".mcp.json"),
    '{"mcpServers":{"sample":{"command":"bun","args":["../escape"]}}}',
  );
  await expect(buildPackage(f.root, f.output, f.commit)).rejects.toThrow(
    "INVALID_CONNECTOR_ENTRY",
  );
  await writeFile(join(f.connector, ".mcp.json"), f.files[".mcp.json"]);
  await rm(join(f.connector, "node_modules"), { recursive: true });
  await expect(buildPackage(f.root, f.output, f.commit)).rejects.toThrow();
  await mkdir(join(f.connector, "node_modules"));
  await symlink("icon.svg", join(f.connector, "link"));
  await expect(buildPackage(f.root, f.output, f.commit)).rejects.toThrow(
    "BUNDLE_SYMLINK",
  );
});

test(".git 없는 context의 extra도 소비 시 git 목록과 대조해 거절한다", async () => {
  const f = await fixture();
  await rename(join(f.root, ".git"), join(f.work, "git"));
  await writeFile(join(f.connector, "untracked.txt"), "extra");
  await buildPackage(f.root, f.output, f.commit);
  await rename(join(f.work, "git"), join(f.root, ".git"));
  await expect(verifyPackage(f.root, f.output, f.commit)).rejects.toThrow(
    "BUNDLE_INVENTORY_MISMATCH",
  );
});

test("build가 같은 경로의 출력을 덮어쓰지 않는다", async () => {
  const f = await fixture();
  await buildPackage(f.root, f.output, f.commit);
  const before = await readFile(join(f.output, "manifest.json"));
  await expect(buildPackage(f.root, f.output, f.commit)).rejects.toThrow();
  expect((await readFile(join(f.output, "manifest.json"))).equals(before)).toBe(
    true,
  );
});

test("실제 빌드 출력이 두 번 다르면 묶음을 남기지 않는다", async () => {
  const f = await fixture();
  const source = f.files["scripts/build.ts"].replace(
    'if (!result.success) throw new Error("BUILD_FAILED");',
    'if (!result.success) throw new Error("BUILD_FAILED"); await Bun.write(result.outputs[0].path, (await result.outputs[0].text()) + crypto.randomUUID());',
  );
  await writeFile(join(f.connector, "scripts/build.ts"), source);
  await expect(buildPackage(f.root, f.output, f.commit)).rejects.toThrow(
    "CONNECTOR_BUILD_NOT_REPRODUCIBLE",
  );
  await expect(lstat(f.output)).rejects.toThrow();
});

test("git mode가 symlink거나 index가 다른 SHA의 목록이면 거절한다", async () => {
  const f = await fixture();
  await buildPackage(f.root, f.output, f.commit);
  f.git("update-index", "--chmod=+x", "sample/icon.svg");
  await expect(verifyPackage(f.root, f.output, f.commit)).rejects.toThrow();
  f.git("reset", "--quiet", "HEAD");
  await symlink("icon.svg", join(f.connector, "link"));
  f.git("add", "sample/link");
  await expect(verifyPackage(f.root, f.output, f.commit)).rejects.toThrow();
});
