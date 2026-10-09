import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const REPO_ROOT = join(import.meta.dirname, "../../");

/** 루트와 모듈의 `docs/` 바로 아래에 둘 수 있는 이름이다. 규칙은 ADR-20261009 / docs-per-module 이 정한다. */
const FIXED_NAMES = new Set(["prd.md", "flow.md", "code-architecture.md", "data-schema.md"]);

/** 정해진 이름 밖에 두는 문서와 그 까닭이다. 까닭이 없으면 더하지 않는다. */
export const EXCEPTIONS: Record<string, string> = {
  "docs/privacy.md": "바깥 주소가 고정이다. Google OAuth 동의 화면이 이 경로를 가리킨다",
  "hermes/docs/hermes-contract.md": "우리 모듈이 아니라 upstream Hermes 의 동작이고 hermes_contract.py 와 짝이다",
  "docs/self-hosting.md": "구현 전인 계획서가 이 파일의 환경 변수 표를 고친다. 그 계획이 끝나면 README 와 설정 파일 주석으로 옮긴다",
};

/** `docs/` 밖에서 둘 수 있는 Markdown 이다. 입구와 규칙, 코드 옆 README, 스킬과 커넥터가 읽는 파일이다. */
const OUTSIDE_DOCS = [
  /^(?:.+\/)?README\.md$/,
  /^README\.ko\.md$/,
  /^(?:.+\/)?(?:AGENTS|CLAUDE)\.md$/,
  /\/skills\/(?:.+\/)?SKILL\.md$/,
  /^hermes\/connectors\/[^/]+\/ICON-SOURCE\.md$/,
  /^tasks\//,
];

/** 한 파일이 둘 수 있는 자리인지 판정한다. ADR 은 `adr-index.test.ts` 가 따로 본다. */
export function problemOf(file: string): string | undefined {
  if (/(^|\/)docs\/adr\//.test(file)) return undefined;
  const inDocs = /^((?:backend|web|hermes)\/)?docs\/(.+)$/.exec(file);
  if (inDocs) {
    const rest = inDocs[2];
    if (rest.startsWith("images/")) return undefined;
    if (rest.includes("/")) return "docs 아래에 하위 디렉터리를 두지 않는다";
    if (FIXED_NAMES.has(rest) || file in EXCEPTIONS) return undefined;
    return "정해진 이름이 아니다. 새 주제는 prd, flow, code-architecture, data-schema 의 절로 더한다";
  }
  if (OUTSIDE_DOCS.some((pattern) => pattern.test(file))) return undefined;
  return "docs 밖에는 README, AGENTS, 스킬 파일만 둔다";
}

function markdownFiles(): string[] {
  return execFileSync("git", ["ls-files", "-z", "-co", "--exclude-standard", "*.md"], {
    cwd: REPO_ROOT,
    encoding: "utf8",
  })
    .split("\0")
    .filter((file) => file !== "" && existsSync(join(REPO_ROOT, file)));
}

test("Markdown 파일은 정해진 이름과 예외 자리에만 있다", () => {
  const problems = markdownFiles().flatMap((file) => {
    const problem = problemOf(file);
    return problem ? [`${file}: ${problem}`] : [];
  });
  assert.deepEqual(problems, []);
});

test("예외 목록의 파일이 있다", () => {
  const missing = Object.keys(EXCEPTIONS).filter((file) => !existsSync(join(REPO_ROOT, file)));
  assert.deepEqual(missing, []);
});

test("모듈 docs 의 정해진 이름과 예외는 통과하고 새 주제 파일은 실패한다", () => {
  for (const file of ["docs/prd.md", "backend/docs/data-schema.md", "web/docs/flow.md", "docs/privacy.md", "docs/adr/INDEX.md", "backend/docs/adr/archive/ADR-006-x.md"]) {
    assert.equal(problemOf(file), undefined, file);
  }
  for (const file of ["docs/connectors.md", "backend/docs/memory.md", "web/docs/frontend/chat.md", "notes.md", "hermes/plugins/x/NOTES.md"]) {
    assert.notEqual(problemOf(file), undefined, file);
  }
});

test("코드 옆 README 와 스킬, 계획서는 docs 밖에 둘 수 있다", () => {
  for (const file of ["README.md", "README.ko.md", "hermes/plugins/fos-ctx/README.md", "backend/gradle/README.md", "hermes/AGENTS.md", "hermes/connectors/gmail/skills/mail/SKILL.md", "tasks/plan1-x/phase-01.md"]) {
    assert.equal(problemOf(file), undefined, file);
  }
});
