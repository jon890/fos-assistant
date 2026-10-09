#!/usr/bin/env node
// 기능 파일 머리의 `covers:` 경로를 PR 이 바꿨는데 그 기능 파일은 바꾸지 않았으면 경고만 낸다(ADR-20261009 / feature-docs).
// 이름 바꾸기나 포맷처럼 문서를 고칠 일이 없는 변경도 그 경로를 바꾸므로 실패로 막지 않는다. 종료 코드는 늘 0 이다.
import { execFileSync } from "node:child_process";
import { appendFileSync, existsSync, readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

/** 저장소 root(현재 작업 디렉터리) 기준의 기능 파일 디렉터리다. */
const FEATURES_DIR = "docs/features/";
/** 경고 한 줄에 이름을 적는 바뀐 파일 수다. 나머지는 개수만 적는다. */
const LISTED = 3;

/**
 * 첫 `##` 제목 앞에서 `covers:` 로 시작하는 줄의 백틱 경로를 순서대로 낸다. 코드 펜스 안은 보지 않는다.
 * @param {string} markdown
 * @returns {string[]}
 */
export function parseCovers(markdown) {
  const paths = [];
  let fence = null;
  for (const line of markdown.split(/\r?\n/)) {
    const marker = /^\s*(`{3,}|~{3,})/.exec(line)?.[1];
    if (fence) {
      if (marker && marker[0] === fence[0] && marker.length >= fence.length) fence = null;
      continue;
    }
    if (marker) {
      fence = marker;
      continue;
    }
    if (/^##\s/.test(line)) break;
    if (!line.startsWith("covers:")) continue;
    for (const match of line.matchAll(/`([^`]+)`/g)) paths.push(match[1].trim());
  }
  return paths;
}

/**
 * `path` 가 `file` 을 포함하는지 본다.
 * `*` 가 있으면 glob 이다. `**` 는 경로 마디 여럿(없어도 된다)과, `*` 는 한 마디 안의 글자와 맞는다.
 * 그 밖에는 `/` 로 끝나면 디렉터리, 아니면 파일 하나다.
 * @param {string} file
 * @param {string} path
 * @returns {boolean}
 */
export function coveredBy(file, path) {
  if (path.includes("*")) {
    const pattern = path
      .split(/(\*\*\/|\*\*|\*)/)
      .map((part) => {
        if (part === "**/") return "(?:.*/)?";
        if (part === "**") return ".*";
        if (part === "*") return "[^/]*";
        return part.replace(/[.+?^${}()|[\]\\]/g, "\\$&");
      })
      .join("");
    return new RegExp(`^${pattern}$`).test(file);
  }
  return path.endsWith("/") ? file.startsWith(path) : file === path;
}

/**
 * covers 아래 파일이 바뀌었는데 기능 파일 자체는 바뀌지 않은 기능을 기능 파일 이름 순서로 낸다.
 * 기능 파일을 지우거나 이름을 바꾼 것도 `changed` 에 들어 있으므로 고친 것으로 본다.
 * @param {string[]} changed 바뀐 파일의 저장소 root 기준 경로
 * @param {Map<string, string[]>} features 기능 파일 경로와 그 covers 경로
 * @returns {Array<{ feature: string; files: string[] }>}
 */
export function staleFeatures(changed, features) {
  const touched = new Set(changed);
  const stale = [];
  for (const feature of [...features.keys()].sort()) {
    if (touched.has(feature)) continue;
    const covers = features.get(feature) ?? [];
    const files = changed.filter((file) => covers.some((path) => coveredBy(file, path)));
    if (files.length) stale.push({ feature, files });
  }
  return stale;
}

/**
 * 경고 문장이다. 바뀐 파일은 처음 셋과 나머지 개수만 적는다.
 * @param {string[]} files
 * @returns {string}
 */
export function warningMessage(files) {
  const listed = files.slice(0, LISTED).join(", ");
  const rest = files.length > LISTED ? ` 외 ${files.length - LISTED}개` : "";
  return `이 PR 이 covers 경로의 파일 ${files.length}개(${listed}${rest})를 바꿨는데 이 기능 파일은 바꾸지 않았다. 흐름이나 갈리는 지점이 바뀌었으면 이 파일도 고친다.`;
}

/** GitHub Actions 명령의 값에서 줄바꿈과 `%` 를 이스케이프한다. */
function escapeCommand(text) {
  return text.replace(/%/g, "%25").replace(/\r/g, "%0D").replace(/\n/g, "%0A");
}

/** 현재 작업 디렉터리의 `docs/features/*.md` 와 그 covers 경로를 읽는다. */
function readFeatures() {
  const features = new Map();
  if (!existsSync(FEATURES_DIR)) return features;
  for (const name of readdirSync(FEATURES_DIR)) {
    if (!name.endsWith(".md")) continue;
    const feature = `${FEATURES_DIR}${name}`;
    features.set(feature, parseCovers(readFileSync(feature, "utf8")));
  }
  return features;
}

function main([base, head = "HEAD"]) {
  if (!base) {
    console.log("알림: 비교할 base 가 없어 기능 문서 covers 검사를 건너뛴다. 사용법: node scripts/check-feature-covers.mjs <base> [head]");
    return;
  }
  let changed;
  try {
    // -z 는 한글 경로가 따옴표로 바뀌지 않게 하고, --no-renames 는 이름 바뀜을 옛 경로와 새 경로 둘로 낸다.
    changed = execFileSync("git", ["diff", "--name-only", "--no-renames", "-z", `${base}...${head}`], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    })
      .split("\0")
      .filter(Boolean);
  } catch (error) {
    const reason = String(error.stderr || error.message).trim().split("\n")[0];
    console.log(`알림: ${base}...${head} 를 비교하지 못해 기능 문서 covers 검사를 건너뛴다. ${reason}`);
    return;
  }
  const stale = staleFeatures(changed, readFeatures());
  const summary = [];
  for (const { feature, files } of stale) {
    const message = warningMessage(files);
    console.log(`::warning file=${feature}::${escapeCommand(message)}`);
    summary.push(`- \`${feature}\`: ${message}`);
  }
  console.log(`기능 문서 covers 검사: 바뀐 파일 ${changed.length}개, 경고 ${stale.length}개 (기준: ${base}...${head})`);
  const summaryFile = process.env.GITHUB_STEP_SUMMARY;
  if (summaryFile && summary.length) {
    try {
      appendFileSync(summaryFile, `### 기능 문서 covers 검사\n\n${summary.join("\n")}\n`);
    } catch (error) {
      console.log(`알림: 작업 요약에 경고를 적지 못했다. ${error.message}`);
    }
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    console.log(`알림: 기능 문서 covers 검사가 중간에 멈췄다. ${error.message}`);
  }
  process.exitCode = 0;
}
