import { execFileSync } from "node:child_process";
import {
  chmod,
  lstat,
  mkdir,
  mkdtemp,
  readdir,
  readFile,
  rm,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

export const BUN_VERSION = "1.3.14";
type Mode = "0644" | "0755";
type Connector = {
  entry: string;
  files: Record<string, string>;
  modes: Record<string, Mode>;
};
type Manifest = {
  format: number;
  source_commit: string;
  bun_version: string;
  connectors: Record<string, Connector>;
};
type File = { name: string; mode: Mode };

function requireValue(value: unknown, code: string): asserts value {
  if (!value) throw new Error(code);
}

function checkCommit(commit: string) {
  requireValue(/^[a-f0-9]{40}$/.test(commit), "INVALID_SOURCE_COMMIT");
}

function safePath(name: string) {
  requireValue(
    typeof name === "string" &&
      name.length > 0 &&
      !name.includes("\\") &&
      !/[\x00-\x1f\x7f]/.test(name) &&
      name
        .split("/")
        .every((part) => part !== "" && part !== "." && part !== ".."),
    "UNSAFE_BUNDLE_PATH",
  );
}

function hash(bytes: Uint8Array) {
  return new Bun.CryptoHasher("sha256").update(bytes).digest("hex");
}

function sorted(value: unknown): unknown {
  if (value !== null && typeof value === "object" && !Array.isArray(value))
    return Object.fromEntries(
      Object.entries(value)
        .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
        .map(([key, item]) => [key, sorted(item)]),
    );
  return value;
}

function sameKeys(actual: object, expected: string[]) {
  requireValue(
    JSON.stringify(Object.keys(actual).sort()) ===
      JSON.stringify(expected.sort()),
    "BUNDLE_INVENTORY_MISMATCH",
  );
}

async function directory(directory: string, exactMode = false) {
  const stat = await lstat(directory);
  requireValue(
    stat.isDirectory() && !stat.isSymbolicLink(),
    "BUNDLE_NOT_DIRECTORY",
  );
  if (exactMode)
    requireValue((stat.mode & 0o7777) === 0o755, "BUNDLE_MODE_MISMATCH");
}

// node_modules만 제외하고, dist 안의 다른 추적 파일도 보존한다.
async function walk(
  root: string,
  skip: Set<string> = new Set(),
  exactMode = false,
): Promise<{ files: File[]; directories: string[] }> {
  await directory(root, exactMode);
  const files: File[] = [];
  const directories: string[] = [];
  async function visit(prefix: string) {
    for (const name of (await readdir(join(root, prefix))).sort()) {
      const item = prefix ? `${prefix}/${name}` : name;
      safePath(item);
      if (skip.has(item)) continue;
      const stat = await lstat(join(root, item));
      requireValue(!stat.isSymbolicLink(), "BUNDLE_SYMLINK");
      if (stat.isDirectory()) {
        if (exactMode)
          requireValue((stat.mode & 0o7777) === 0o755, "BUNDLE_MODE_MISMATCH");
        directories.push(item);
        await visit(item);
      } else {
        requireValue(stat.isFile(), "BUNDLE_SPECIAL_FILE");
        const mode: Mode = stat.mode & 0o111 ? "0755" : "0644";
        if (exactMode)
          requireValue(
            (stat.mode & 0o7777) === Number.parseInt(mode, 8),
            "BUNDLE_MODE_MISMATCH",
          );
        files.push({ name: item, mode });
      }
    }
  }
  await visit("");
  return { files, directories };
}

async function connectors(root: string) {
  await directory(root);
  const result: Record<string, string> = {};
  for (const name of (await readdir(root)).sort()) {
    if (name === ".git") continue;
    const stat = await lstat(join(root, name));
    requireValue(!stat.isSymbolicLink(), "BUNDLE_SYMLINK");
    if (!stat.isDirectory()) continue;
    requireValue(/^[a-z][a-z0-9-]*$/.test(name), "INVALID_CONNECTOR_ID");
    const metadata = JSON.parse(
      await readFile(join(root, name, "connector.json"), "utf8"),
    );
    requireValue(metadata.id === name, "CONNECTOR_ID_MISMATCH");
    const mcp = JSON.parse(
      await readFile(join(root, name, ".mcp.json"), "utf8"),
    );
    sameKeys(mcp.mcpServers, [name]);
    const server = mcp.mcpServers[name];
    const entry = `dist/${name}-mcp.js`;
    const args = JSON.stringify(server.args);
    const bundle = `\${CLAUDE_PLUGIN_ROOT}/${entry}`;
    requireValue(
      server.command === "bun" &&
        (args === JSON.stringify([bundle]) ||
          args === JSON.stringify(["--no-env-file", bundle])),
      "INVALID_CONNECTOR_ENTRY",
    );
    result[name] = entry;
  }
  requireValue(Object.keys(result).length > 0, "NO_CONNECTORS");
  return result;
}

function git(root: string, args: string[]) {
  return execFileSync("git", ["-C", root, ...args], {
    maxBuffer: 32 * 1024 * 1024,
    stdio: ["ignore", "pipe", "pipe"],
  });
}

// .git 없는 Docker context는 목록을 증명할 수 없다. verify는 checkout의 git 목록을 요구한다.
function inventory(root: string, id: string): Map<string, Mode> {
  const result = new Map<string, Mode>();
  for (const record of git(root, ["ls-files", "--stage", "-z", "--", `${id}/`])
    .toString()
    .split("\0")
    .filter(Boolean)) {
    const [header, name] = record.split("\t");
    const [mode, , stage] = header.split(" ");
    requireValue(
      stage === "0" && (mode === "100644" || mode === "100755"),
      "UNSUPPORTED_GIT_MODE",
    );
    // git -C의 ls-files 경로는 해당 디렉터리를 기준으로 나온다.
    requireValue(name.startsWith(`${id}/`), "GIT_INVENTORY_MISMATCH");
    const local = name.slice(id.length + 1);
    safePath(local);
    requireValue(!local.startsWith("node_modules/"), "TRACKED_NODE_MODULES");
    result.set(local, mode === "100755" ? "0755" : "0644");
  }
  requireValue(result.size > 0, "GIT_INVENTORY_MISMATCH");
  return result;
}

export async function buildPackage(
  root: string,
  output: string,
  commit: string,
) {
  checkCommit(commit);
  requireValue(Bun.version === BUN_VERSION, "BUN_VERSION_MISMATCH");
  root = resolve(root);
  output = resolve(output);
  requireValue(
    output !== root && !output.startsWith(`${root}/`),
    "OUTPUT_INSIDE_SOURCE",
  );
  const entries = await connectors(root);
  // 기존 출력을 덮어쓰지 않는다. 실패하면 이번에 만든 출력만 지운다.
  await mkdir(output, { mode: 0o755 });
  await chmod(output, 0o755);
  const temporary = await mkdtemp(join(tmpdir(), "connector-build-"));
  const manifest: Manifest = {
    format: 1,
    source_commit: commit,
    bun_version: BUN_VERSION,
    connectors: {},
  };
  try {
    for (const [id, entry] of Object.entries(entries)) {
      const source = join(root, id);
      await directory(join(source, "node_modules"));
      let sourceFiles = (await walk(source, new Set(["node_modules", entry])))
        .files;
      const { buildBundle } = await import(
        pathToFileURL(join(source, "scripts/build.ts")).href
      );
      requireValue(
        typeof buildBundle === "function",
        "INVALID_BUILD_DEFINITION",
      );
      const first = join(temporary, id, "a");
      const second = join(temporary, id, "b");
      await buildBundle(first);
      await buildBundle(second);
      const filename = entry.slice("dist/".length);
      sameKeys(
        Object.fromEntries(
          (await walk(first)).files.map((file) => [file.name, true]),
        ),
        [filename],
      );
      sameKeys(
        Object.fromEntries(
          (await walk(second)).files.map((file) => [file.name, true]),
        ),
        [filename],
      );
      const built = await readFile(join(first, filename));
      requireValue(
        built.equals(await readFile(join(second, filename))),
        `CONNECTOR_BUILD_NOT_REPRODUCIBLE: ${id}`,
      );
      let tracked: Map<string, Mode> | undefined;
      try {
        git(root, ["rev-parse", "--show-toplevel"]);
        tracked = inventory(root, id);
      } catch (error) {
        const failure = error as { status?: number; code?: string };
        if (failure.status !== 128 && failure.code !== "ENOENT") throw error;
      }
      if (tracked) {
        sourceFiles = sourceFiles.filter((file) => tracked.has(file.name));
        sameKeys(
          Object.fromEntries(sourceFiles.map((file) => [file.name, true])),
          [...tracked.keys()].filter((name) => name !== entry),
        );
      }
      const connector: Connector = { entry, files: {}, modes: {} };
      for (const file of [
        ...sourceFiles,
        { name: entry, mode: "0644" as Mode },
      ].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))) {
        const bytes =
          file.name === entry ? built : await readFile(join(source, file.name));
        const mode = tracked?.get(file.name) ?? file.mode;
        const destination = join(output, "hermes/connectors", id, file.name);
        await mkdir(dirname(destination), { recursive: true, mode: 0o755 });
        await writeFile(destination, bytes, { mode: Number.parseInt(mode, 8) });
        await chmod(destination, Number.parseInt(mode, 8));
        connector.files[file.name] = hash(bytes);
        connector.modes[file.name] = mode;
      }
      manifest.connectors[id] = connector;
    }
    // umask와 관계없이 출력 디렉터리의 권한을 맞춘다.
    for (const name of (await walk(output)).directories)
      await chmod(join(output, name), 0o755);
    await writeFile(
      join(output, "manifest.json"),
      JSON.stringify(sorted(manifest), null, 2) + "\n",
      { mode: 0o644 },
    );
    await chmod(join(output, "manifest.json"), 0o644);
  } catch (error) {
    await rm(output, { recursive: true, force: true });
    throw error;
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
}

export async function verifyPackage(
  root: string,
  output: string,
  commit: string,
) {
  checkCommit(commit);
  const actual = await walk(output, new Set(), true);
  const manifest: Manifest = JSON.parse(
    await readFile(join(output, "manifest.json"), "utf8"),
  );
  sameKeys(manifest, ["format", "source_commit", "bun_version", "connectors"]);
  requireValue(
    manifest.format === 1 &&
      manifest.source_commit === commit &&
      manifest.bun_version === BUN_VERSION,
    "BUNDLE_MANIFEST_MISMATCH",
  );
  requireValue(
    git(root, ["rev-parse", "HEAD"]).toString().trim() === commit,
    "SOURCE_CHECKOUT_MISMATCH",
  );
  const entries = await connectors(root);
  sameKeys(manifest.connectors, Object.keys(entries));
  const expected = ["manifest.json"];
  for (const [id, entry] of Object.entries(entries)) {
    git(root, ["diff", "--cached", "--quiet", commit, "--", `${id}/`]);
    const connector = manifest.connectors[id];
    sameKeys(connector, ["entry", "files", "modes"]);
    requireValue(connector.entry === entry, "INVALID_CONNECTOR_ENTRY");
    const tracked = inventory(root, id);
    const names = [...new Set([...tracked.keys(), entry])];
    sameKeys(connector.files, names);
    sameKeys(connector.modes, names);
    const prefix =
      git(root, ["rev-parse", "--show-prefix"]).toString().trim() + id;
    for (const name of names) {
      safePath(name);
      const full = `hermes/connectors/${id}/${name}`;
      const bytes = await readFile(join(output, full));
      requireValue(
        /^[a-f0-9]{64}$/.test(connector.files[name]) &&
          hash(bytes) === connector.files[name],
        "BUNDLE_HASH_MISMATCH",
      );
      const mode = tracked.get(name) ?? "0644";
      requireValue(
        connector.modes[name] === mode &&
          ((await lstat(join(output, full))).mode & 0o7777) ===
            Number.parseInt(mode, 8),
        "BUNDLE_MODE_MISMATCH",
      );
      if (tracked.has(name))
        requireValue(
          bytes.equals(git(root, ["show", `${commit}:${prefix}/${name}`])),
          "BUNDLE_SOURCE_MISMATCH",
        );
      expected.push(full);
    }
  }
  sameKeys(
    Object.fromEntries(actual.files.map((file) => [file.name, true])),
    expected,
  );
  const directories = new Set<string>();
  for (const name of expected) {
    let parent = dirname(name);
    while (parent !== ".") {
      directories.add(parent);
      parent = dirname(parent);
    }
  }
  sameKeys(Object.fromEntries(actual.directories.map((name) => [name, true])), [
    ...directories,
  ]);
}

if (import.meta.main) {
  const [action, output, commit, ...extra] = Bun.argv.slice(2);
  requireValue(
    (action === "build" || action === "verify") &&
      output &&
      commit &&
      extra.length === 0,
    "USAGE: package.ts <build|verify> <output> <SOURCE_COMMIT>",
  );
  await (action === "build" ? buildPackage : verifyPackage)(
    import.meta.dir,
    output,
    commit,
  );
}
