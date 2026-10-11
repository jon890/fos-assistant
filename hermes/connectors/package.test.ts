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
async function fixture(args = ["${CLAUDE_PLUGIN_ROOT}/dist/sample-mcp.js"]) {
  const work = await mkdtemp(join(tmpdir(), "connector-package-test-"));
  temporary.push(work);
  const root = join(work, "source");
  const connector = join(root, "sample");
  const files = {
    "connector.json": '{"id":"sample"}\n',
    ".mcp.json":
      JSON.stringify({ mcpServers: { sample: { command: "bun", args } } }) +
      "\n",
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

test("dotenv 차단 옵션과 단일 entry를 실제 빌드와 설치 묶음에 그대로 보존한다", async () => {
  const f = await fixture([
    "--no-env-file",
    "${CLAUDE_PLUGIN_ROOT}/dist/sample-mcp.js",
  ]);
  await buildPackage(f.root, f.output, f.commit);
  await verifyPackage(f.root, f.output, f.commit);
  const installed = join(f.output, "hermes/connectors/sample");
  const bytes = await readFile(join(installed, ".mcp.json"), "utf8");
  expect(bytes).toBe(f.files[".mcp.json"]);
  const server = JSON.parse(bytes).mcpServers.sample;
  expect(server.command).toBe("bun");
  expect(server.args).toEqual([
    "--no-env-file",
    "${CLAUDE_PLUGIN_ROOT}/dist/sample-mcp.js",
  ]);
  expect(
    execFileSync(
      process.execPath,
      server.args.map((arg: string) =>
        arg.replace("${CLAUDE_PLUGIN_ROOT}", installed),
      ),
      { encoding: "utf8" },
    ),
  ).toBe("sample ready\n");
});

const entry = "${CLAUDE_PLUGIN_ROOT}/dist/sample-mcp.js";
const invalidLaunches: [string, string, unknown][] = [
  ["다른 command", "python3", [entry]],
  ["빈 인자", "bun", []],
  ["entry 누락", "bun", ["--no-env-file"]],
  ["다른 옵션", "bun", ["--env-file", entry]],
  ["옵션 오타", "bun", ["--no-env-files", entry]],
  ["옵션 중복", "bun", ["--no-env-file", "--no-env-file", entry]],
  ["옵션 순서", "bun", [entry, "--no-env-file"]],
  ["단일 entry 뒤 추가 옵션", "bun", [entry, "--arbitrary-flag"]],
  [
    "차단 옵션 뒤 추가 옵션",
    "bun",
    ["--no-env-file", "--arbitrary-flag", entry],
  ],
  ["entry 중복", "bun", ["--no-env-file", entry, entry]],
  [
    "다른 bundle",
    "bun",
    ["--no-env-file", "${CLAUDE_PLUGIN_ROOT}/dist/other-mcp.js"],
  ],
  [
    "source 경로",
    "bun",
    ["--no-env-file", "${CLAUDE_PLUGIN_ROOT}/src/server.ts"],
  ],
  [
    "상위 경로",
    "bun",
    ["--no-env-file", "${CLAUDE_PLUGIN_ROOT}/dist/../dist/sample-mcp.js"],
  ],
  ["문자열 인자", "bun", entry],
];
for (const [name, command, args] of invalidLaunches) {
  test(`생산 계약이 실행 선언을 거절한다: ${name}`, async () => {
    const f = await fixture();
    await buildPackage(f.root, f.output, f.commit);
    await writeFile(
      join(f.connector, ".mcp.json"),
      JSON.stringify({ mcpServers: { sample: { command, args } } }),
    );
    await expect(
      buildPackage(f.root, join(f.work, "bad"), f.commit),
    ).rejects.toThrow("INVALID_CONNECTOR_ENTRY");
    await expect(verifyPackage(f.root, f.output, f.commit)).rejects.toThrow(
      "INVALID_CONNECTOR_ENTRY",
    );
  });
}

test("설치 선언에서 dotenv 차단을 지우고 해시까지 바꿔도 source 바이트 대조로 거절한다", async () => {
  const f = await fixture(["--no-env-file", entry]);
  await buildPackage(f.root, f.output, f.commit);
  const bytes = JSON.stringify({
    mcpServers: { sample: { command: "bun", args: [entry] } },
  });
  await writeFile(join(f.output, "hermes/connectors/sample/.mcp.json"), bytes);
  const name = join(f.output, "manifest.json");
  const manifest = JSON.parse(await readFile(name, "utf8"));
  manifest.connectors.sample.files[".mcp.json"] = new Bun.CryptoHasher("sha256")
    .update(bytes)
    .digest("hex");
  await writeFile(name, JSON.stringify(manifest));
  await expect(verifyPackage(f.root, f.output, f.commit)).rejects.toThrow(
    "BUNDLE_SOURCE_MISMATCH",
  );
});

test("두 독립 빌드의 전체 바이트, 설치 파일과 git 실행 권한을 보존한다", async () => {
  const f = await fixture();
  expect(f.git("ls-files", "sample/dist/sample-mcp.js")).toBe("");
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

test("추적하지 않는 오래된 entry를 무시하고 source와 index를 바꾸지 않는다", async () => {
  const f = await fixture();
  const entry = join(f.connector, "dist/sample-mcp.js");
  await writeFile(entry, "stale generated entry\n");
  const before = f.git("ls-files", "--stage");
  await buildPackage(f.root, f.output, f.commit);
  await verifyPackage(f.root, f.output, f.commit);
  expect(await readFile(entry, "utf8")).toBe("stale generated entry\n");
  expect(f.git("ls-files", "--stage")).toBe(before);
  expect(f.git("diff", "--exit-code")).toBe("");
  expect(
    execFileSync(
      process.execPath,
      [join(f.output, "hermes/connectors/sample/dist/sample-mcp.js")],
      { encoding: "utf8" },
    ),
  ).toBe("sample ready\n");
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
