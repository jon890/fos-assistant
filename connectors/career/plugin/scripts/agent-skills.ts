/**
 * `agent-skills/` 의 비서용 스킬을 검사한다. 스킬은 모두 이 디렉터리가 원본이다.
 *
 * fos-assistant 는 스킬마다 `SKILL.md` 와 `references/`, `templates/` 만 복사하고, 스킬은 에이전트의 셸 실행 공간에서 돈다.
 * 그래서 다른 실행 환경에서만 쓰는 도구나 문장이 스킬에 남으면 에이전트가 없는 도구를 찾다 멈춘다.
 *
 *   bun run scripts/agent-skills.ts          문제가 있으면 종료 코드 1 (파일을 쓰지 않는다)
 *   bun run scripts/agent-skills.ts --check  위와 같다
 */
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";

const pluginRoot = join(import.meta.dir, "..");
export const AGENT_SKILLS_DIRECTORY = join(pluginRoot, "agent-skills");

/**
 * 다른 실행 환경에서만 쓰는 도구나 문장을 막는 패턴이다. 스킬이 이런 말을 담으면 비서용 표현으로 고쳐 쓴다.
 */
export const HOST_ONLY_PATTERNS: readonly RegExp[] = [
  /Claude Code/,
  /하위 에이전트|서브에이전트|subagent/i,
  /\b(WebFetch|WebSearch|AskUserQuestion|TodoWrite|NotebookEdit)\b/,
  // `Read` 도구, Read 도구, `Read`/`Write` 도구 처럼 도구 이름을 나열한 꼴을 모두 잡는다.
  /\b`?(Agent|Task|Skill|Write|Edit|Read|Bash|Grep|Glob)`?(\s*[/,·]\s*`?(Agent|Task|Skill|Write|Edit|Read|Bash|Grep|Glob)`?)*\s*도구/,
  // `${CLAUDE_PLUGIN_ROOT}` 는 실행 공간이 넘기는 env 라 잡지 않는다.
  /\$ARGUMENTS|allowed-tools|CLAUDE_PLUGIN_DATA|CLAUDE_PROJECT_DIR|~\/\.claude\b/,
];

export function hostOnlyFindings(path: string, content: string): string[] {
  return HOST_ONLY_PATTERNS.filter((pattern) => pattern.test(content)).map(
    (pattern) => `${path}: 다른 실행 환경 전용 표현이 있다 ${pattern}`,
  );
}

/** 비서용 스킬이다. 셸에 요구하는 env 는 `assistant.json` 의 `agent_skills.sandbox_env` 가 갖는다. */
export const AGENT_SKILLS: readonly string[] = [
  "application-package-writer",
  "career-connector",
  "interview-practice",
  "interview-question-prep",
  "position-recommender",
  "resume-preparer",
  "resume-review",
  "study-collection",
];

function filesUnder(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true })
    .sort((left, right) => left.name.localeCompare(right.name))
    .flatMap((entry) => {
      const path = join(directory, entry.name);
      if (entry.isSymbolicLink()) throw new Error(`링크는 스킬에 넣지 않는다: ${path}`);
      return entry.isDirectory() ? filesUnder(path) : [path];
    });
}

/** 스킬 목록과 디렉터리의 어긋남, 빠진 `SKILL.md`, 다른 실행 환경 전용 표현이다. 비었으면 맞다. */
export function agentSkillProblems(): string[] {
  const directories = readdirSync(AGENT_SKILLS_DIRECTORY, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name);
  const problems: string[] = [];
  for (const name of directories) if (!AGENT_SKILLS.includes(name)) problems.push(`${name}: 스킬 목록에 없는 디렉터리다`);
  for (const name of AGENT_SKILLS) {
    const directory = join(AGENT_SKILLS_DIRECTORY, name);
    if (!existsSync(join(directory, "SKILL.md"))) {
      problems.push(`${name}: 스킬 목록에 있는데 SKILL.md 가 없다`);
      continue;
    }
    for (const file of filesUnder(directory))
      problems.push(...hostOnlyFindings(`${name}/${relative(directory, file)}`, readFileSync(file, "utf8")));
  }
  return problems;
}

if (import.meta.main) {
  const problems = agentSkillProblems();
  for (const problem of problems) console.error(problem);
  if (problems.length > 0) process.exit(1);
}
