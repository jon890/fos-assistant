import { execFileSync, spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";

const REGRESSION_SPECS = [
  "now.spec.ts",
  "persona.spec.ts",
  "usage.spec.ts",
  "fixture-isolation.spec.ts",
];
const SHARED_FILES = new Set([
  "test/browser/fixtures.ts",
  "test/browser/helpers.ts",
  "test/browser/settings.ts",
  "test/browser/playwright.config.ts",
  "test/browser/web-server.ts",
  "test/e2e/fake-hermes.ts",
  "web/package.json",
  "web/pnpm-lock.yaml",
  "web/next.config.ts",
  "web/src/components/shell/app-shell.tsx",
  "web/src/components/shell/admin-shell.tsx",
  "web/src/components/shell/screen-transition.tsx",
]);

/** 바뀐 spec은 모두, 공통 환경을 바꿨으면 회귀 묶음도 반복한다. 파일을 지운 것은 실행하지 않는다. */
export function selectSpecs(files, daily = false) {
  const selected = new Set(
    daily || files.some((file) => SHARED_FILES.has(file) || file.startsWith("test/e2e/fake-hermes/"))
      ? REGRESSION_SPECS
      : [],
  );
  for (const file of files) {
    if (/^test\/browser\/[^/]+\.spec\.ts$/.test(file))
      selected.add(file.slice("test/browser/".length));
  }
  return [...selected].sort();
}

function main() {
  const base = process.env.BROWSER_STABILITY_BASE;
  const files = base
    ? execFileSync(
        "git",
        ["diff", "--name-only", "--diff-filter=ACMR", base, "HEAD"],
        { encoding: "utf8" },
      )
        .trim()
        .split("\n")
    : [];
  const specs = selectSpecs(
    files,
    process.env.GITHUB_EVENT_NAME === "schedule",
  ).filter((spec) => existsSync(resolve("test/browser", spec)));
  if (specs.length === 0) {
    console.log("반복할 브라우저 spec이 없다.");
    return;
  }
  console.log(`브라우저 연속 3회 검사: ${specs.join(", ")}`);
  const result = spawnSync(
    "pnpm",
    [
      "--dir",
      "web",
      "test:browser",
      ...specs,
      ...process.argv.slice(2),
      "--repeat-each=3",
      "--retries=0",
      "--reporter=list,json",
    ],
    { stdio: "inherit" },
  );
  if (result.error) throw result.error;
  process.exitCode = result.status ?? 1;
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
)
  main();
