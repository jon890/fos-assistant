import { expect, test } from "bun:test";
import { existsSync, lstatSync, readdirSync, readFileSync, realpathSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { AGENT_SKILLS, AGENT_SKILLS_DIRECTORY, agentSkillProblems, hostOnlyFindings } from "./agent-skills.ts";

const pluginRoot = join(import.meta.dir, "..");
const connector = JSON.parse(readFileSync(join(pluginRoot, "assistant.json"), "utf8"));
const plugin = JSON.parse(readFileSync(join(pluginRoot, ".claude-plugin", "plugin.json"), "utf8"));

// 대시보드 plugin 이 manifest 를 읽을 때 거는 규칙을 옮겨 둔다. 어기면 이 플러그인이 카탈로그에서 통째로 빠진다.
// 원본: hermes/plugins/dashboard-profile-api 의 connector_agent_skills.py(`_plugin_and_agent_skills`),
// connector_skills.py(`_connector_skills`, `_skill_name`), connector_schema.py(상한), common.py(`SKILL_NAME_RE`),
// sandbox.py(`SANDBOX_ALLOWED_ENV`). 그쪽 규칙이 바뀌면 여기도 고친다.
// `CAREER_BACKEND_*` 두 이름은 그쪽 허용 목록에서 뺄 때까지 남아 있다.
const SANDBOX_ALLOWED_ENV = new Set([
  "CAREER_BACKEND_TOKEN_FILE",
  "CLAUDE_PLUGIN_ROOT",
  "CAREER_EVIDENCE_DIR",
  "CAREER_WORKSPACE_ROOT",
  "CAREER_DART_API_KEY_FILE",
  "CAREER_BACKEND_URL",
]);
const SKILL_NAME_RE = /^[a-z0-9][a-z0-9._-]{0,63}$/;
const SKILL_PARTS = ["references", "templates"];
const MAX_FILES = 20;
const MAX_CHARS = 100_000;
const SECRET_FRONT_MATTER = /^(required_environment_variables|required_credential_files|setup|prerequisites):/m;

function frontMatter(text: string): string {
  const end = text.indexOf("\n---\n", 4);
  if (!text.startsWith("---\n") || end < 0) throw new Error("앞머리가 없거나 닫히지 않는다");
  return text.slice(4, end);
}

function skillNameOf(directory: string): string {
  return frontMatter(readFileSync(join(directory, "SKILL.md"), "utf8")).match(/^name: (.+)$/m)?.[1] ?? directory.split("/").pop()!;
}

function skillDirectories(root: string): string[] {
  return readdirSync(root, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && existsSync(join(root, entry.name, "SKILL.md")))
    .map((entry) => join(root, entry.name));
}

function walk(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? walk(path) : [path];
  });
}

const agentSkills = connector.agent_skills as { path: string; sandbox_env?: Record<string, string[]> };
const agentRoot = resolve(pluginRoot, agentSkills.path);
const agentSkillDirs = skillDirectories(agentRoot);
const agentSkillNames = agentSkillDirs.map(skillNameOf);

test("agent_skills 는 path 와 sandbox_env 만 받고 path 는 plugin 안의 링크 없는 디렉터리다", () => {
  expect(Object.keys(agentSkills).filter((key) => key !== "path" && key !== "sandbox_env")).toEqual([]);
  expect(typeof agentSkills.path).toBe("string");
  expect(agentRoot).toBe(AGENT_SKILLS_DIRECTORY);
  expect(relative(pluginRoot, agentRoot).startsWith("..")).toBe(false);
  expect(lstatSync(agentRoot).isDirectory()).toBe(true);
  expect(realpathSync(agentRoot)).toBe(realpathSync(pluginRoot) + "/" + relative(pluginRoot, agentRoot));
});

test("에이전트용 스킬 파일은 링크가 없고, 복사되는 파일이 상한 안이며, 앞머리가 비밀값을 요청하지 않는다", () => {
  for (const directory of agentSkillDirs) {
    for (const file of walk(directory)) expect(lstatSync(file).isSymbolicLink(), file).toBe(false);
    const copied = [join(directory, "SKILL.md"), ...SKILL_PARTS.flatMap((part) => (existsSync(join(directory, part)) ? walk(join(directory, part)) : []))];
    expect(copied.length, directory).toBeLessThanOrEqual(MAX_FILES);
    for (const file of copied) expect(readFileSync(file, "utf8").length, file).toBeLessThanOrEqual(MAX_CHARS);
    expect(SECRET_FRONT_MATTER.test(frontMatter(readFileSync(join(directory, "SKILL.md"), "utf8"))), directory).toBe(false);
  }
});

test("에이전트용 스킬 이름은 경로로 쓸 수 있고 서로, 그리고 plugin.json 스킬과 겹치지 않는다", () => {
  const pluginSkillNames = (plugin.skills as string[]).flatMap((path) =>
    skillDirectories(resolve(pluginRoot, path)).map(skillNameOf),
  );
  for (const name of agentSkillNames) expect(SKILL_NAME_RE.test(name) && !name.includes(".."), name).toBe(true);
  expect(new Set(agentSkillNames).size).toBe(agentSkillNames.length);
  expect(agentSkillNames.filter((name) => pluginSkillNames.includes(name))).toEqual([]);
});

test("sandbox_env 는 있는 에이전트용 스킬만 가리키고, 값은 실행 공간 정책이 받는 env 이름의 중복 없는 목록이다", () => {
  for (const [name, names] of Object.entries(agentSkills.sandbox_env ?? {})) {
    expect(agentSkillNames, name).toContain(name);
    expect(Array.isArray(names) && names.length > 0, name).toBe(true);
    expect(new Set(names).size, name).toBe(names.length);
    for (const env of names) expect(SANDBOX_ALLOWED_ENV.has(env), `${name}: ${env}`).toBe(true);
  }
});

test("실행기를 부르는 스킬은 실행기와 작업 폴더에 필요한 env 를 sandbox_env 로 요구한다", () => {
  // 요구하지 않으면 셸 조건을 보지 않고 늘 설치돼, 셸이 없는 에이전트에서도 돌 수 없는 스킬이 보인다.
  for (const directory of agentSkillDirs) {
    if (!readFileSync(join(directory, "SKILL.md"), "utf8").includes("${CLAUDE_PLUGIN_ROOT}")) continue;
    const name = skillNameOf(directory);
    expect(agentSkills.sandbox_env?.[name] ?? [], name).toEqual(
      expect.arrayContaining(["CLAUDE_PLUGIN_ROOT", "CAREER_WORKSPACE_ROOT"]),
    );
  }
});

test("근거 원본 검사를 부르는 스킬은 프로젝트 근거 위치를 sandbox_env 로 요구한다", () => {
  // `package check-sources` 는 `CAREER_EVIDENCE_DIR` 가 없으면 늘 `unavailable` 이라 스킬이 매번 멈춘다.
  for (const directory of agentSkillDirs) {
    const skill = readFileSync(join(directory, "SKILL.md"), "utf8");
    if (!skill.includes("<CAREER_LOCAL> package check-sources")) continue;
    const name = skillNameOf(directory);
    expect(agentSkills.sandbox_env?.[name] ?? [], name).toContain("CAREER_EVIDENCE_DIR");
  }
  for (const name of ["application-package-writer", "resume-preparer"]) expect(agentSkills.sandbox_env?.[name], name).toContain("CAREER_EVIDENCE_DIR");
});

test("비서용 스킬과 그 sandbox_env 는 커리어 Backend 의 주소와 token 을 요구하지 않는다", () => {
  // 공고·공부 추천과 면접 연습은 작업 폴더의 SQLite 를 쓴다Backend 값을 요구하면 그 값을 내린 뒤 스킬이 빠진다.
  for (const names of Object.values(agentSkills.sandbox_env ?? {})) {
    expect(names.filter((name) => name.startsWith("CAREER_BACKEND"))).toEqual([]);
  }
  for (const directory of agentSkillDirs) {
    const files = (readdirSync(directory, { recursive: true }) as string[])
      .map((name) => join(directory, name))
      .filter((file) => file.endsWith(".md"));
    for (const file of files) {
      expect({ file, hit: /CAREER_BACKEND_/.test(readFileSync(file, "utf8")) }).toEqual({ file, hit: false });
    }
  }
});

test("agent-skills 의 디렉터리가 스킬 목록과 같고 검사기가 문제를 찾지 못한다", () => {
  expect(agentSkillProblems()).toEqual([]);
  expect([...agentSkillNames].sort()).toEqual([...AGENT_SKILLS].sort());
});

test("노트북 전용 스킬 디렉터리가 없다", () => {
  // 스킬은 모두 `agent-skills/` 가 원본이다. `skills/` 가 다시 생기면 노트북에서만 도는 스킬이 따로 생긴 것이다.
  expect(existsSync(join(pluginRoot, "skills"))).toBe(false);
});

// 지운 MCP 도구다. 스킬이 이 이름을 적으면 에이전트가 없는 도구를 찾다 멈춘다.
const REMOVED_TOOLS = [
  "list_context_documents",
  "get_context_document",
  "save_context_document",
  "list_profile_documents",
  "get_profile_document",
  "save_profile_document",
  "list_usage_snapshots",
  "get_interview_questions",
  "list_personal_questions",
  "save_interview_attempt",
  "save_personal_question",
  "get_study_candidates",
  "save_study_recommendation",
  "list_study_candidates",
  "list_study_recommendations",
  "get_position_research_constraints",
  "check_position_exclusions",
];

test("비서용 스킬은 지운 MCP 도구와 지운 대화용 스킬을 가리키지 않고, 적은 MCP 도구는 선언에 있다", () => {
  for (const directory of agentSkillDirs) {
    for (const file of walk(directory)) {
      const text = readFileSync(file, "utf8");
      for (const tool of REMOVED_TOOLS) expect({ file, tool, hit: text.includes(tool) }).toEqual({ file, tool, hit: false });
      expect({ file, hit: /대화용|`study-topic-recommender`/.test(text) }).toEqual({ file, hit: false });
      const mentioned = text.match(/`((?:check|get|update)_[a-z_]+)`/g) ?? [];
      for (const name of mentioned) expect(Object.keys(connector.tools), file).toContain(name.slice(1, -1));
    }
  }
});

test("맥락 문서를 쓰는 비서용 스킬은 기억에서 읽고, 같은 제목이 둘 이상이면 멈춘다", () => {
  const titles: Record<string, string[]> = {
    "position-recommender": ["커리어: 역할 선호", "커리어: 지원 상태"],
    "study-collection": ["커리어: 학습 관심사"],
    "interview-practice": ["커리어: 현재 경력", "커리어: 지원 상태"],
    "career-connector": ["커리어: 현재 경력", "커리어: 역할 선호", "커리어: 지원 상태", "커리어: 학습 관심사"],
    "application-package-writer": ["커리어: 현재 경력", "커리어: 역할 선호", "커리어: 지원 상태"],
    "resume-preparer": ["커리어: 현재 경력", "커리어: 역할 선호"],
    "interview-question-prep": ["커리어: 현재 경력", "커리어: 지원 상태"],
    // 맥락 문서를 직접 읽지 않는다. 원고와 근거, 다른 스킬의 기준 문서만 읽는다.
    "resume-review": [],
  };
  expect(Object.keys(titles).sort()).toEqual([...agentSkillNames].sort());
  for (const [name, expected] of Object.entries(titles)) {
    if (expected.length === 0) continue;
    const skill = readFileSync(join(agentRoot, name, "SKILL.md"), "utf8");
    for (const title of expected) expect(skill, `${name}: ${title}`).toContain(`「${title}」`);
    expect(skill, name).toContain("memory_search");
    expect(skill, name).toContain("memory_read");
    expect(skill, name).toContain("같은 제목의 기억이 둘 이상이면");
    // 실측(2026-11-23): 맥락 문서가 없자 비서가 제목이 다른 기억으로 대신했다.
    expect(skill, name).toContain("제목이 정확히 같은 기억만 맥락 문서다.");
  }
});

test("맥락 문서가 없으면 실행기를 부르지 않고, 문서를 만드는 스킬을 안내한다", () => {
  const connector = readFileSync(join(agentRoot, "career-connector", "SKILL.md"), "utf8");
  expect(connector).toContain("## 맥락 문서 읽고 고치기");
  for (const name of ["position-recommender", "study-collection", "interview-practice", "application-package-writer", "resume-preparer", "interview-question-prep"]) {
    const skill = readFileSync(join(agentRoot, name, "SKILL.md"), "utf8");
    const line = skill.split("\n").find((text) => text.includes("없으면 실행기를 부르지 않"));
    expect(line, name).toContain("`career-connector` 스킬의 「맥락 문서 읽고 고치기」");
    expect(line, name).toContain("끝낸다");
  }
});

test("실행기가 실패하면 웹 검색이나 직접 만든 결과로 대신하지 않는다", () => {
  // 실측(2026-11-23): `collect` 가 죽은 뒤 `finalize` 를 부르고, 그것도 실패하자 웹 검색으로 공고를 찾았다.
  for (const name of agentSkillNames) {
    const skill = readFileSync(join(agentRoot, name, "SKILL.md"), "utf8");
    const line = skill.split("\n").find((text) => text.includes("실행기") && text.includes("실패하면") && text.includes("대신하지 않"));
    expect(line, name).toBeDefined();
  }
  const position = readFileSync(join(agentRoot, "position-recommender", "SKILL.md"), "utf8");
  const rule = position.split("\n").find((text) => text.includes("대신하지 않") && text.includes("실행기 명령"))!;
  for (const command of ["collect", "commit-company-tiers", "commit-analyses", "finalize"]) expect(rule).toContain(`\`${command}\``);
  const failures = readFileSync(join(agentRoot, "position-recommender", "references", "failures.md"), "utf8");
  expect(failures).toContain("## 실행기 실패");
  expect(failures).toContain("`collect` 가 실패했는데 `finalize` 를 부르지 않는다.");
  const interview = readFileSync(join(agentRoot, "interview-practice", "SKILL.md"), "utf8");
  expect(interview).toContain("실행기 없이 질문을 지어내거나");
});

test("비서용 스킬은 스킬 파일을 고치지 않는다고 적는다", () => {
  // 실측(2026-11-23): 비서가 절차가 막히자 스킬 편집 도구로 스킬을 고치려 했다.
  for (const directory of agentSkillDirs)
    expect(readFileSync(join(directory, "SKILL.md"), "utf8"), directory).toContain("스킬 파일은 고치지 않는다.");
});

test("공고 추천 스킬은 description 앞에 요청 문구를 두고, 앞쪽 순서 절과 웹 검색 금지 줄을 갖는다", () => {
  // 실측(2026-11-23): 비서가 스킬을 다 읽고도 `collect` 를 건너뛰고 웹 검색으로 공고를 찾았다.
  // 비서의 스킬 목록은 description 을 약 50자에서 자른다.
  const skill = readFileSync(join(agentRoot, "position-recommender", "SKILL.md"), "utf8");
  const description = frontMatter(skill).match(/^description: (.+)$/m)?.[1] ?? "";
  expect(description.slice(0, 50)).toMatch(/공고 추천|포지션 추천|채용공고/);
  const sections = [...skill.matchAll(/^## (.+)$/gm)].map((match) => match[1]);
  expect(sections.slice(0, 2)).toEqual(["실행 환경", "순서"]);
  const order = skill.slice(skill.indexOf("## 순서"), skill.indexOf("\n## ", skill.indexOf("## 순서") + 1));
  const steps = ["collect", "finalize", "file_space_put"].map((word) => order.indexOf(word));
  expect(steps.every((index) => index >= 0)).toBe(true);
  expect([...steps].sort((left, right) => left - right)).toEqual(steps);
  expect(order.split("\n").some((line) => line.includes("web_search") && line.includes("않"))).toBe(true);
});

// 스킬이 적은 실행기 명령을 번들한 실행기에 묻는 검사는 로컬 실행기 번들(`career-local.js`)을 옮길 때 함께 옮긴다.
test("면접 연습 스킬이 실행기 명령을 모두 적고, 문서 관리 스킬이 프로필 원고 자리와 기억 저장 도구를 안내한다", () => {
  const interview = readFileSync(join(agentRoot, "interview-practice", "SKILL.md"), "utf8");
  const commands = [...interview.matchAll(/<CAREER_LOCAL> interview ((?:personal )?[a-z]+)/g)].map((match) => match[1]!);
  expect([...new Set(commands)].sort()).toEqual(["personal add", "personal disable", "personal list", "progress", "record", "select"]);
  const connectorSkill = readFileSync(join(agentRoot, "career-connector", "SKILL.md"), "utf8");
  expect(connectorSkill).toContain("library/profiles/");
  expect(connectorSkill).toContain("memory_remember");
  // 사용량 차트와 Tokens 배지는 README 에서 뺀다.
  expect(connectorSkill).toContain("Tokens 배지를 넣지 않는다");
});

test("비서용 스킬에는 Claude Code 전용 안내와 복사되지 않는 파일이 없다", () => {
  for (const directory of agentSkillDirs) {
    for (const file of walk(directory)) {
      const path = relative(directory, file);
      expect(path === "SKILL.md" || SKILL_PARTS.some((part) => path.startsWith(`${part}/`)), file).toBe(true);
      expect(hostOnlyFindings(file, readFileSync(file, "utf8")), file).toEqual([]);
    }
  }
});

test("다른 실행 환경 전용 표현 검사는 실행 환경 문장, 하위 에이전트와 그 환경의 도구 이름을 잡는다", () => {
  for (const text of [
    "이 스킬은 Claude Code 에서만 돈다.",
    "공고마다 하위 에이전트를 띄운다.",
    "WebFetch 로 회사 블로그를 읽는다.",
    "`Write` 도구로 결과 파일을 쓴다.",
    "~/.claude/skills 를 읽는다.",
    "~/.claude 아래를 본다.",
    "Read 도구로 큐를 읽는다.",
    "`Read`/`Write` 도구로 결과를 고친다.",
    "인자는 $ARGUMENTS 로 받는다.",
    "allowed-tools: Bash",
    "${CLAUDE_PLUGIN_DATA} 에 남긴다.",
    "${CLAUDE_PROJECT_DIR} 에서 연다.",
  ])
    expect(hostOnlyFindings("sample.md", text), text).not.toEqual([]);
  expect(hostOnlyFindings("sample.md", "`write_file` 로 결과 파일을 쓴다. `${CLAUDE_PLUGIN_ROOT}` 는 셸이 푼다.")).toEqual([]);
});

test("실행기를 부르는 스킬은 플러그인 폴더의 원본을 읽지 않고 이 스킬의 절차만 따르라고 적는다", () => {
  // 실측(2026-11-21): 비서 에이전트가 `skills/` 원본의 실행 환경 문장을 읽고 수집 뒤에 멈췄다.
  for (const directory of agentSkillDirs) {
    const skill = readFileSync(join(directory, "SKILL.md"), "utf8");
    if (!skill.includes("${CLAUDE_PLUGIN_ROOT}")) continue;
    expect(skill, directory).toContain("플러그인 폴더의 다른 파일에서 절차를 찾지 않는다.");
  }
});

test("plugin.json 의 skills 가 agent-skills 를 가리키지 않는다", () => {
  // 비서용 스킬은 `assistant.json` 의 `agent_skills` 로만 설치한다. 여기서도 가리키면 셸 조건을 보지 않고 설치된다.
  expect(plugin.skills).toEqual([]);
});

const applicationSkills = ["application-package-writer", "resume-preparer", "resume-review", "interview-question-prep"];

function markdownOf(name: string): Array<{ file: string; text: string }> {
  return walk(join(agentRoot, name))
    .filter((file) => file.endsWith(".md"))
    .map((file) => ({ file, text: readFileSync(file, "utf8") }));
}

test("비서용 스킬은 지운 작업본 동기화와 공통 프로필 명령, 스킬 밖을 가리키는 링크를 담지 않는다", () => {
  for (const name of agentSkillNames)
    for (const { file, text } of markdownOf(name))
      for (const banned of ["workspace begin", "workspace finish", "<CAREER_LOCAL> application-profile", "](../", "--env-file=", "sources/fos-study"])
        expect({ file, banned, hit: text.includes(banned) }).toEqual({ file, banned, hit: false });
});

// 실행기 이름과 package, resume 하위 명령이 번들 사용법에 있는지는 로컬 실행기 번들과 함께 옮긴다.
test("지원 준비 스킬이 package, resume 의 주요 하위 명령을 적고 workspace 는 paths 만 쓴다", () => {
  const seen = new Set<string>();
  for (const name of applicationSkills)
    for (const { file, text } of markdownOf(name)) {
      for (const match of text.matchAll(/<CAREER_LOCAL> (package|resume) ([a-z][a-z-]*)/g)) seen.add(`${match[1]} ${match[2]}`);
      for (const match of text.matchAll(/<CAREER_LOCAL> workspace ([a-z]+)/g)) expect({ file, command: match[1] }).toEqual({ file, command: "paths" });
    }
  for (const command of ["package check-sources", "package validate", "package render", "package report", "package question-schema", "resume export", "resume check-html", "resume validate-ledger", "resume build-bundle"])
    expect([...seen], command).toContain(command);
});

test("본문이 가리키는 references 파일이 모두 있고, 다른 스킬의 문서를 가리키면 skill_view 로 읽으라고 적는다", () => {
  for (const name of agentSkillNames) {
    const skill = readFileSync(join(agentRoot, name, "SKILL.md"), "utf8");
    // 「`resume-preparer` 스킬의 `references/x.md`」 처럼 다른 스킬 이름 뒤에 온 경로는 그 스킬에서 찾는다.
    for (const match of skill.matchAll(/(?:`([a-z-]+)` 스킬의 )?(?:\]\(|`)references\/([A-Za-z0-9._-]+\.md)/g)) {
      const owner = match[1] ?? name;
      expect(existsSync(join(agentRoot, owner, "references", match[2]!)), `${name}: ${owner}/references/${match[2]}`).toBe(true);
      if (owner !== name) expect(skill, name).toContain("`skill_view`");
    }
  }
  for (const name of ["application-package-writer", "resume-review", "interview-question-prep"])
    expect(readFileSync(join(agentRoot, name, "SKILL.md"), "utf8"), name).toContain("`skill_view`");
});

test("지원 준비 스킬은 description 앞에 요청 문구를 두고, HTML 결과를 사본으로 만들어 「내 파일」 에 넣는다", () => {
  // 비서의 스킬 목록은 description 을 약 50자에서 자른다.
  const requests: Record<string, RegExp> = {
    "application-package-writer": /지원 준비|지원 패키지/,
    "resume-preparer": /이력서 준비|이력서 작성/,
    "resume-review": /이력서 평가|이력서 검토/,
    "interview-question-prep": /공고별 면접 질문/,
  };
  for (const [name, pattern] of Object.entries(requests)) {
    const skill = readFileSync(join(agentRoot, name, "SKILL.md"), "utf8");
    expect((frontMatter(skill).match(/^description: (.+)$/m)?.[1] ?? "").slice(0, 50), name).toMatch(pattern);
  }
  // 공고별 면접 질문 연습은 HTML 결과가 없다. 연습 기록이 작업 폴더의 SQLite 에 남는다.
  for (const name of ["application-package-writer", "resume-preparer", "resume-review"]) {
    const skill = readFileSync(join(agentRoot, name, "SKILL.md"), "utf8");
    expect(skill, name).toContain("<CAREER_LOCAL> package report");
    const line = skill.split("\n").find((text) => text.includes("file_space_put") && text.includes("건너뛰지 않는다"));
    expect(line, name).toContain("`리포트 사본:` 줄의 경로");
    expect(skill, name).toContain("「내 파일」 줄");
  }
});

test("이력서 준비 스킬은 PDF 도구가 없으면 HTML 만 전달하고, 지원 패키지 스킬은 공통 프로필을 기억에서 읽고 사본을 지운다", () => {
  const resume = readFileSync(join(agentRoot, "resume-preparer", "SKILL.md"), "utf8");
  expect(resume).toContain("`PDF 도구 없음`");
  expect(resume).toContain("7단계의 제출 묶음은 만들지 않는다");
  const application = readFileSync(join(agentRoot, "application-package-writer", "SKILL.md"), "utf8");
  expect(application).toContain("「커리어: 지원서 공통 프로필」");
  expect(application).toContain("context/application-profile.md");
  expect(application).toContain("지원서 입력을 만든 뒤 그 파일을 지운다.");
});
