import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { join, posix } from "node:path";
import test from "node:test";
import { blankFences } from "./markdown.ts";

const REPO_ROOT = join(import.meta.dirname, "../../");

/**
 * 문서가 백틱으로 적은 코드 이름이 저장소에 있는지 본다. 문서끼리의 링크는 `doc-links.test.ts`,
 * 코드가 문서를 가리키는 방향은 `doc-references.test.ts` 가 본다.
 * 정밀도가 높은 범주만 실패로 내고, 그 밖의 대문자 스네이크 값과 클래스 이름 단독은 `알림:` 으로만 알린다.
 */

/** 이 시험이 자기 예시 문자열로 코드 낱말을 채우지 않게 낱말 집합에서 뺀다. */
const SELF = "test/unit/doc-code-references.test.ts";

/** 저장소 최상위 항목이다. 경로의 첫 마디가 이 가운데 하나일 때만 저장소 경로로 본다. 나머지는 Hermes core 같은 바깥 경로다. */
const TOP_LEVEL = new Set(["backend", "web", "hermes", "scripts", "test", "docs", "tasks", ".github"]);

/** backend 의 Java 패키지 root 다. `hermes/HermesModelClient` 처럼 확장자 없이 패키지 기준으로 줄여 쓴 표기를 여기서 찾는다. */
const JAVA_PACKAGE_ROOTS = ["backend/src/main/java/com/bifos/assistant", "backend/src/test/java/com/bifos/assistant"];

/** 경로가 아니라 파일 이름의 확장자로 읽는 꼬리다. `Foo.java` 를 `클래스.멤버` 로 읽지 않는다. */
const FILE_EXTENSION =
  /\.(?:md|java|kt|kts|ts|tsx|js|mjs|py|json|ya?ml|sh|sql|txt|html|css|xml|toml|properties|gradle|env|example|lock|png|svg)$/;

const HTTP_METHOD = /^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s+/;

/** 줄 끝의 예외 표기다. 까닭을 반드시 적는다. */
const REF_IGNORE = /<!--\s*ref-ignore:(.*?)-->\s*$/;

export type Category = "path" | "api" | "config" | "member" | "env" | "upperSnake" | "className";

/** 실패로 내는 범주다. 나머지는 경고다. */
const FAILING: ReadonlySet<Category> = new Set(["path", "api", "config", "member", "env"]);

export interface DocToken {
  text: string;
  /** 1부터 세는 줄 번호다. */
  line: number;
}

export interface ScannedDocument {
  tokens: DocToken[];
  /** 까닭이 빈 `ref-ignore` 표기의 줄 번호다. */
  emptyIgnores: number[];
}

/**
 * 한 문서에서 인라인 백틱 토큰을 뽑는다. 코드 펜스 안과 바깥 주소 링크의 글자 안은 보지 않는다.
 * 줄 끝에 `<!-- ref-ignore: 까닭 -->` 이 있는 줄은 건너뛰고, 까닭이 비었으면 그 줄을 따로 낸다.
 */
export function scanDocument(markdown: string): ScannedDocument {
  const tokens: DocToken[] = [];
  const emptyIgnores: number[] = [];
  blankFences(markdown).forEach((lineText, index) => {
    const ignore = REF_IGNORE.exec(lineText);
    if (ignore) {
      if (ignore[1].trim() === "") emptyIgnores.push(index + 1);
      return;
    }
    const prose = lineText.replace(/\[(?:[^\]]*)\]\((?:[a-z][a-z0-9+.-]*:)[^)]*\)/gi, "");
    for (const match of prose.matchAll(/`([^`\n]+)`/g)) {
      tokens.push({ text: match[1].trim(), line: index + 1 });
    }
  });
  return { tokens, emptyIgnores };
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** `<…>` 나 `{…}` 하나로 이루어진 마디다. 아무 마디와 맞는다. */
function isWildcardSegment(segment: string): boolean {
  return /^(?:<[^<>]*>|\{[^{}]*\})$/.test(segment);
}

/** 마디 일부에만 자리표시자가 들었거나 `*`, `NNN`, `XXX`, `...`, `…` 가 든 토큰이다. 예시로 보고 건너뛴다. */
export function isPlaceholder(token: string): boolean {
  if (/\*|NNN|XXX|\.\.\.|…/.test(token)) return true;
  return token
    .split("/")
    .some((segment) => /[<>{}]/.test(segment) && !isWildcardSegment(segment));
}

/** 범주를 판정한다. 검사할 모양이 아니면 `undefined` 다. 앞에서 맞는 범주가 이긴다. */
export function classifyToken(token: string): Category | undefined {
  const firstWord = token.replace(/<[^<>]*>/g, "<>").split(/\s+/)[0];
  if (/^\/api\/v1\//.test(token.replace(HTTP_METHOD, ""))) return "api";
  if (firstWord.includes("/")) {
    const head = firstWord.replace(/^\.\//, "").split("/")[0];
    return TOP_LEVEL.has(head) ? "path" : undefined;
  }
  if (/\s/.test(token)) return undefined;
  if (/^assistant(?:\.[A-Za-z0-9-]+)+$/.test(token) && !FILE_EXTENSION.test(token)) return "config";
  if (/^ASSISTANT_[A-Z0-9_]+(?:=.*)?$/.test(token)) return "env";
  if (/^[A-Z][\w$]*[.#][A-Za-z_$][\w$]*(?:\(.*\))?$/.test(token) && !FILE_EXTENSION.test(token)) {
    return "member";
  }
  if (/^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+$/.test(token)) return "upperSnake";
  if (/^[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+$/.test(token)) return "className";
  return undefined;
}

export interface PathIndex {
  files: ReadonlySet<string>;
  directories: ReadonlySet<string>;
}

/** 경로 토큰을 저장소 root 기준 후보로 바꾼다. 문서 자리와 문서가 속한 모듈 기준도 차례로 본다. */
export function pathCandidates(token: string, docFile: string): string[] {
  const path = token
    .replace(/<[^<>]*>/g, (placeholder) => placeholder.replace(/\s/g, "_"))
    .split(/\s+/)[0]
    .replace(/^\.\//, "")
    .replace(/(?::\d+(?:-\d+)?|#L\d+(?:-L?\d+)?)$/, "");
  const module = docFile.split("/")[0];
  const bases = ["", posix.dirname(docFile), TOP_LEVEL.has(module) && module !== "docs" ? module : ""];
  return [...new Set(bases.map((base) => posix.normalize(posix.join(base, path))))];
}

/** 경로가 저장소에 있는지 본다. 끝이 `/` 면 디렉터리만, 마디 전체가 자리표시자면 아무 마디와 맞춘다. */
export function resolvePath(token: string, docFile: string, index: PathIndex): boolean {
  const wantsDirectory = token.split(/\s+/)[0].endsWith("/");
  for (const candidate of pathCandidates(token, docFile)) {
    const path = candidate.replace(/\/$/, "");
    const segments = path.split("/");
    if (segments.some(isWildcardSegment)) {
      const pattern = new RegExp(`^${segments.map((s) => (isWildcardSegment(s) ? "[^/]+" : escapeRegExp(s))).join("/")}$`);
      const pool = wantsDirectory ? [...index.directories] : [...index.files, ...index.directories];
      if (pool.some((entry) => pattern.test(entry))) return true;
      continue;
    }
    if (index.directories.has(path)) return true;
    if (!wantsDirectory && index.files.has(path)) return true;
  }
  // 확장자 없이 Java 패키지 기준으로 줄여 쓴 클래스(`hermes/HermesModelClient`)다
  const [first] = pathCandidates(token, "");
  if (wantsDirectory || /\.\w+$/.test(first)) return false;
  return JAVA_PACKAGE_ROOTS.some((root) => index.files.has(`${root}/${first}.java`));
}

export interface Route {
  /** 메서드 매핑이 아니라 `@RequestMapping` 이면 `undefined` 다. */
  method?: string;
  path: string;
}

/** 어노테이션 인자에서 경로 문자열을 고른다. `value =` 나 `path =` 가 있으면 그 값을, 없으면 이름 없는 첫 인자를 본다. */
function mappingPaths(args: string | undefined): string[] {
  if (args === undefined) return [""];
  const named = /\b(?:value|path)\s*=\s*/.exec(args);
  let piece = named ? args.slice(named.index + named[0].length) : args;
  const nextName = /,\s*\w+\s*=/.exec(piece);
  if (nextName) piece = piece.slice(0, nextName.index);
  if (!named && /^\s*\w+\s*=/.test(piece)) return [""];
  const paths = [...piece.matchAll(/"([^"]*)"/g)].map((match) => match[1]);
  return paths.length > 0 ? paths : [""];
}

/** Java 소스 한 파일의 Spring 매핑을 모은다. 클래스 선언 앞의 `@RequestMapping` 은 접두로 붙인다. */
export function springRoutes(java: string): Route[] {
  const classAt = /\b(?:class|record|interface)\s+[A-Z]\w*/.exec(java)?.index ?? 0;
  const annotation = /@(Request|Get|Post|Put|Patch|Delete)Mapping\b(?:\s*\(([^)]*)\))?/g;
  let prefixes = [""];
  const routes: Route[] = [];
  for (const match of java.matchAll(annotation)) {
    const paths = mappingPaths(match[2]);
    if (match[1] === "Request" && (match.index ?? 0) < classAt) {
      prefixes = paths;
      continue;
    }
    const method = match[1] === "Request" ? undefined : match[1].toUpperCase();
    for (const prefix of prefixes) {
      for (const path of paths) routes.push({ method, path: prefix + path });
    }
  }
  return routes;
}

/** 소스의 문자열 가운데 `/api/v1/` 로 시작하는 것을 경로로 모은다. 템플릿의 `${…}` 는 자리표시자 마디로 바꾼다. */
export function stringRoutes(source: string): Route[] {
  return [...source.matchAll(/["'`](\/api\/v1\/[^"'`\s?#]*)/g)].map((match) => ({
    path: match[1].replace(/\$\{[^}]*\}/g, "{}"),
  }));
}

/** `hermes/connectors/<id>/` 아래 파일이면 그 커넥터 디렉터리를 낸다. */
export function connectorOf(file: string): string | undefined {
  return /^hermes\/connectors\/[^/]+(?=\/)/.exec(file)?.[0];
}

/** `GET /api/v1/x/{id}?a=b` 를 메서드와 마디로 나눈다. 띄어쓰기가 든 `<session id>` 도 한 마디로 읽는다. */
export function parseApiToken(token: string): { method?: string; segments: string[] } {
  const method = HTTP_METHOD.exec(token)?.[1];
  const path = token
    .replace(HTTP_METHOD, "")
    .replace(/<[^<>]*>/g, "{}")
    .split(/\s+/)[0]
    .split(/[?#]/)[0]
    .replace(/\/$/, "");
  return { method, segments: path.split("/").slice(1) };
}

function routeSegments(path: string): string[] {
  return path.replace(/\/$/, "").split("/").slice(1);
}

/** 문서의 API 경로가 매핑 하나와 맞는지 본다. 양쪽의 `{…}` 마디는 아무 마디와 맞는다. */
export function matchesRoute(token: string, routes: readonly Route[]): boolean {
  const wanted = parseApiToken(token);
  return routes.some((route) => {
    if (wanted.method && route.method && wanted.method !== route.method) return false;
    const segments = routeSegments(route.path);
    if (segments.length !== wanted.segments.length) return false;
    return segments.every(
      (segment, i) => isWildcardSegment(segment) || isWildcardSegment(wanted.segments[i]) || segment === wanted.segments[i],
    );
  });
}

/** 들여쓰기로 YAML 의 키를 점으로 이어 펼친다. 중간 키도 넣는다. 목록 항목과 블록 문자열 안은 보지 않는다. */
export function flattenYaml(yaml: string): Set<string> {
  const keys = new Set<string>();
  const stack: Array<{ indent: number; key: string }> = [];
  let blockIndent = -1;
  for (const raw of yaml.split("\n")) {
    if (/^\s*(#|$)/.test(raw)) continue;
    const indent = raw.length - raw.trimStart().length;
    if (blockIndent >= 0 && indent > blockIndent) continue;
    blockIndent = -1;
    const match = /^(\s*)([\w.-]+|"[^"]+")\s*:(?:\s+(.*))?$/.exec(raw);
    if (!match) continue;
    while (stack.length > 0 && stack[stack.length - 1].indent >= indent) stack.pop();
    const key = [...stack.map((entry) => entry.key), match[2].replace(/"/g, "")].join(".");
    keys.add(key);
    stack.push({ indent, key: match[2].replace(/"/g, "") });
    if (/^[|>]/.test(match[3] ?? "")) blockIndent = indent;
  }
  return keys;
}

/** Spring 의 느슨한 바인딩처럼 케밥과 카멜을 같게 본다. */
function canonical(key: string): string {
  return key.replace(/-/g, "").toLowerCase();
}

interface CodeIndex {
  paths: PathIndex;
  routes: Route[];
  /** 커넥터 디렉터리마다 그 소스가 문자열로 부르는 `/api/v1/` 경로다. */
  connectorRoutes: Map<string, Route[]>;
  configKeys: Set<string>;
  propertyFiles: Map<string, string[]>;
  backendText: string;
  words: Set<string>;
  /** 설정 키의 하위 마디를 케밥과 카멜 구분 없이 찾는 낱말 집합이다. */
  lowerWords: Set<string>;
  declarations: Map<string, string[]>;
  read: (file: string) => string;
}

const CODE_FILE = /\.(?:java|kt|kts|ts|tsx|js|mjs|py|sh|ya?ml|json|sql|toml|xml|properties|template|example|txt|conf)$|(?:^|\/)(?:Dockerfile|\.env\.[\w.-]+)$/;

function gitLsFiles(): string[] {
  return execFileSync("git", ["ls-files", "-z", "-co", "--exclude-standard"], {
    cwd: REPO_ROOT,
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
  })
    .split("\0")
    .filter((file) => file !== "");
}

/** `.gitignore` 에 걸리는 경로를 한 번에 묻는다. */
function gitIgnored(paths: string[]): Set<string> {
  if (paths.length === 0) return new Set();
  const result = spawnSync("git", ["check-ignore", "--no-index", "--stdin", "-z"], {
    cwd: REPO_ROOT,
    input: paths.join("\0"),
    encoding: "utf8",
  });
  return new Set(result.stdout.split("\0").filter((path) => path !== ""));
}

let cached: CodeIndex | undefined;

function codeIndex(): CodeIndex {
  if (cached) return cached;
  const files = gitLsFiles();
  const directories = new Set<string>();
  for (const file of files) {
    const parts = file.split("/");
    for (let i = 1; i < parts.length; i++) directories.add(parts.slice(0, i).join("/"));
  }
  const texts = new Map<string, string>();
  const read = (file: string): string => {
    let text = texts.get(file);
    if (text === undefined) {
      text = existsSync(join(REPO_ROOT, file)) ? readFileSync(join(REPO_ROOT, file), "utf8") : "";
      texts.set(file, text);
    }
    return text;
  };
  const routes: Route[] = [];
  const connectorRoutes = new Map<string, Route[]>();
  const propertyFiles = new Map<string, string[]>();
  const declarations = new Map<string, string[]>();
  const words = new Set<string>();
  const backend: string[] = [];
  for (const file of files) {
    if (!CODE_FILE.test(file) || file === SELF || file.endsWith("pnpm-lock.yaml")) continue;
    const text = read(file);
    for (const word of text.match(/[A-Za-z_$][\w$]*/g) ?? []) words.add(word);
    const isJava = file.endsWith(".java");
    if (isJava || /\.(?:tsx?|mjs)$/.test(file)) {
      const declaration = isJava
        ? /\b(?:class|record|interface|enum)\s+([A-Z][\w$]*)/g
        : /\b(?:class|interface|type|function|const|enum)\s+([A-Z][\w$]*)/g;
      for (const match of text.matchAll(declaration)) {
        const list = declarations.get(match[1]) ?? [];
        if (!list.includes(file)) list.push(file);
        declarations.set(match[1], list);
      }
    }
    const connector = connectorOf(file);
    if (connector && !file.startsWith(`${connector}/dist/`)) {
      connectorRoutes.set(connector, [...(connectorRoutes.get(connector) ?? []), ...stringRoutes(text)]);
    }
    if (file.startsWith("backend/src/main/")) {
      backend.push(text);
      if (isJava) {
        routes.push(...springRoutes(text));
        for (const match of text.matchAll(/@ConfigurationProperties\(\s*(?:prefix\s*=\s*)?"([^"]+)"/g)) {
          const prefix = canonical(match[1]);
          propertyFiles.set(prefix, [...(propertyFiles.get(prefix) ?? []), file]);
        }
      }
    }
  }
  const configKeys = new Set<string>();
  for (const file of files.filter((f) => /^backend\/src\/main\/resources\/application[^/]*\.ya?ml$/.test(f))) {
    for (const key of flattenYaml(read(file))) configKeys.add(canonical(key));
  }
  cached = {
    paths: { files: new Set(files), directories },
    routes,
    connectorRoutes,
    configKeys,
    propertyFiles,
    backendText: backend.join("\n"),
    words,
    lowerWords: new Set([...words].map((word) => word.toLowerCase())),
    declarations,
    read,
  };
  return cached;
}

/** 설정 키가 `application.yml`, `@ConfigurationProperties` 의 필드, 코드 안의 `${…}` 가운데 하나에 있는지 본다. */
function resolveConfig(key: string, index: CodeIndex): boolean {
  const wanted = canonical(key);
  if (index.configKeys.has(wanted)) return true;
  if (new RegExp(`${escapeRegExp(key)}(?![\\w.-])`).test(index.backendText)) return true;
  for (const [prefix, files] of index.propertyFiles) {
    if (wanted === prefix) return true;
    if (!wanted.startsWith(`${prefix}.`)) continue;
    const [field, ...rest] = wanted.slice(prefix.length + 1).split(".");
    const fieldWords = new Set(
      files.flatMap((file) => (index.read(file).match(/[A-Za-z_]\w*/g) ?? []).map((word) => word.toLowerCase())),
    );
    if (fieldWords.has(field) && rest.every((segment) => index.lowerWords.has(segment))) return true;
  }
  return false;
}

/**
 * 소스에 멤버가 선언이나 클래스 안 호출 모양으로 있는지 본다. `x.stream()` 처럼 `.` 이나 `::`, `#` 뒤에 오는 것은
 * 다른 객체의 멤버라서 세지 않는다. 메서드 `bar(`, 필드와 레코드 칸 `bar;`, `bar =`, `bar,`, `bar)`, TS 칸 `bar:`,
 * 열거 값, 중첩 타입 선언을 본다.
 */
export function declaresMember(source: string, member: string): boolean {
  const name = escapeRegExp(member);
  return new RegExp(
    `(?<![\\w$.:#])${name}(?=\\s*[(;=,):?<{}])|\\b(?:class|interface|record|enum|type)\\s+${name}\\b`,
  ).test(source);
}

/** `클래스.멤버` 의 멤버가 그 클래스를 선언한 파일에 있는지 본다. 저장소에 선언된 클래스가 아니면 `undefined` 다. */
function resolveMember(token: string, index: CodeIndex): boolean | undefined {
  const [, owner, member] = /^([\w$]+)[.#]([\w$]+)/.exec(token) ?? [];
  const files = index.declarations.get(owner);
  if (!files) return undefined;
  return files.some((file) => declaresMember(index.read(file), member));
}

/** 검사하는 문서다. 루트와 모듈의 `docs/` 바로 아래 문서와, 루트 `docs/features/` 의 기능 문서와, 코드 옆 `README.md` 다. ADR 과 `tasks/` 는 뺀다. */
export function isTargetDocument(file: string): boolean {
  if (/(^|\/)adr\//.test(file) || file.startsWith("tasks/")) return false;
  return /^(?:(?:backend|web|hermes)\/)?docs\/[^/]+\.md$/.test(file) || /^docs\/features\/[^/]+\.md$/.test(file) || /.\/README\.md$/.test(file);
}

/**
 * 토큰 하나의 범주를 정하고 저장소에서 찾는다. 검사하지 않는 토큰이면 `undefined` 다.
 * `.gitignore` 확인은 여러 경로를 한 번에 묻도록 `collectFindings` 가 한다.
 */
export function resolveToken(text: string, docFile: string): { category: Category; resolved: boolean } | undefined {
  const category = classifyToken(text);
  if (category === undefined) return undefined;
  if (category === "path" && isPlaceholder(pathCandidates(text, "")[0])) return undefined;
  if (category === "api" && isPlaceholder(`/${parseApiToken(text).segments.join("/")}`)) return undefined;
  const index = codeIndex();
  let resolved: boolean | undefined;
  if (category === "path") resolved = resolvePath(text, docFile, index.paths);
  else if (category === "api") {
    // 커넥터 문서의 API 경로는 그 커넥터가 부르는 바깥 서비스의 경로다. 그 커넥터 소스의 문자열과 맞춘다
    const connector = connectorOf(docFile);
    resolved = matchesRoute(text, connector ? (index.connectorRoutes.get(connector) ?? []) : index.routes);
  } else if (category === "config") resolved = resolveConfig(text, index);
  else if (category === "member") resolved = resolveMember(text, index);
  else if (category === "env") resolved = index.words.has(text.split("=")[0]);
  else if (category === "upperSnake") resolved = index.words.has(text);
  else resolved = index.declarations.has(text) || index.words.has(text);
  return resolved === undefined ? undefined : { category, resolved };
}

export interface Finding {
  where: string;
  token: string;
  category: Category | "ref-ignore";
}

/** 대상 문서를 모두 읽어 실패와 경고, 범주별 검사 수를 낸다. */
export function collectFindings(): { failures: Finding[]; warnings: Finding[]; checked: Map<Category, number> } {
  const index = codeIndex();
  const failures: Finding[] = [];
  const warnings: Finding[] = [];
  const checked = new Map<Category, number>();
  const pathMisses: Array<Finding & { candidates: string[] }> = [];
  for (const file of [...index.paths.files].filter(isTargetDocument).sort()) {
    const { tokens, emptyIgnores } = scanDocument(index.read(file));
    for (const line of emptyIgnores) failures.push({ where: `${file}:${line}`, token: "까닭이 빈 표기", category: "ref-ignore" });
    for (const { text, line } of tokens) {
      const result = resolveToken(text, file);
      if (result === undefined) continue;
      checked.set(result.category, (checked.get(result.category) ?? 0) + 1);
      if (result.resolved) continue;
      const finding = { where: `${file}:${line}`, token: text, category: result.category };
      if (result.category === "path") pathMisses.push({ ...finding, candidates: pathCandidates(text, file) });
      else (FAILING.has(result.category) ? failures : warnings).push(finding);
    }
  }
  // 빌드 결과물처럼 `.gitignore` 에 걸리는 경로는 저장소에 없는 것이 맞다
  const ignored = gitIgnored(pathMisses.flatMap((finding) => finding.candidates));
  for (const { candidates, ...finding } of pathMisses) {
    if (!candidates.some((path) => ignored.has(path))) failures.push(finding);
  }
  return { failures, warnings, checked };
}

test("문서가 백틱으로 적은 저장소 경로, API, 설정 키, 클래스 멤버, 환경 변수가 코드에 있다", () => {
  const { failures, warnings } = collectFindings();
  for (const warning of warnings) {
    console.log(`알림: ${warning.where}  \`${warning.token}\`  ${warning.category} 를 코드에서 찾지 못했다`);
  }
  assert.deepEqual(
    failures.map((finding) => `${finding.where}  ${finding.category}  ${finding.token}`),
    [],
  );
});

test("코드 펜스 안과 바깥 주소 링크의 글자 안은 뽑지 않는다", () => {
  const markdown = "`guide/a.ts`\n```\n`guide/b.ts`\n```\n[`tools/x.py`](https://example.com/x) [`guide/c.md`](c.md)";
  assert.deepEqual(scanDocument(markdown).tokens, [
    { text: "guide/a.ts", line: 1 },
    { text: "guide/c.md", line: 5 },
  ]);
});

test("ref-ignore 줄은 건너뛰고 까닭이 비면 그 줄을 낸다", () => {
  const scanned = scanDocument("`guide/a.ts` <!-- ref-ignore: 지난 이름 -->\n`guide/b.ts` <!-- ref-ignore: -->\n");
  assert.deepEqual(scanned.tokens, []);
  assert.deepEqual(scanned.emptyIgnores, [2]);
});

test("범주는 첫 마디와 모양으로 정한다", () => {
  assert.equal(classifyToken("backend/build.gradle.kts"), "path");
  assert.equal(classifyToken("tools/delegate_tool.py"), undefined);
  assert.equal(classifyToken("POST /api/v1/chat/{id}/stream"), "api");
  assert.equal(classifyToken("assistant.chat.stream-heartbeat"), "config");
  assert.equal(classifyToken("ASSISTANT_CHAT_STREAM_HEARTBEAT"), "env");
  assert.equal(classifyToken("ChatService#stream()"), "member");
  assert.equal(classifyToken("ChatService.java"), undefined);
  assert.equal(classifyToken("PROACTIVE_REPORT_TRIGGER"), "upperSnake");
  assert.equal(classifyToken("AttentionTrigger"), "className");
});

test("마디 일부에만 든 자리표시자와 생략 표기는 건너뛰고 마디 전체의 자리표시자는 검사한다", () => {
  assert.equal(isPlaceholder("guide/<id>/build.ts"), false);
  assert.equal(isPlaceholder("guide/<id>.ts"), true);
  assert.equal(isPlaceholder("guide/*.ts"), true);
  assert.equal(isPlaceholder("guide/NNN-slug.md"), true);
  assert.equal(isPlaceholder("guide/…/a.ts"), true);
});

test("경로는 root, 문서 자리, 문서가 속한 모듈 순으로 찾고 끝이 / 면 디렉터리만 본다", () => {
  const index: PathIndex = {
    files: new Set(["web/guide/a.ts", "web/guide/x/scripts/build.ts", "scripts/run.sh"]),
    directories: new Set(["web", "web/guide", "web/guide/x", "web/guide/x/scripts", "scripts"]),
  };
  assert.equal(resolvePath("scripts/run.sh --all", "guide/a.md", index), true);
  assert.equal(resolvePath("scripts/build.ts", "web/guide/x/README.md", index), true);
  assert.equal(resolvePath("scripts/run.sh/", "guide/a.md", index), false);
  assert.equal(resolvePath("web/guide/<id>/scripts/", "guide/a.md", index), true);
  assert.equal(resolvePath("web/guide/b.ts:12", "guide/a.md", index), false);
});

test("확장자 없는 경로는 backend 의 Java 패키지 기준 클래스로도 찾는다", () => {
  const index: PathIndex = {
    files: new Set(["backend/src/test/java/com/bifos/assistant/guide/GuideTest.java"]),
    directories: new Set(),
  };
  assert.equal(resolvePath("guide/GuideTest", "guide/a.md", index), true);
  assert.equal(resolvePath("guide/GoneTest", "guide/a.md", index), false);
});

test("Spring 매핑은 클래스 접두와 메서드 경로를 잇고 자리표시자 마디는 아무 마디와 맞는다", () => {
  const java = [
    '@RequestMapping("/api/v1/guide")',
    "public class GuideController {",
    '  @GetMapping("/{id}") void one() {}',
    "  @PostMapping(",
    '      path = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE) void stream() {}',
    "  @DeleteMapping void all() {}",
    "}",
  ].join("\n");
  const routes = springRoutes(java);
  assert.deepEqual(routes, [
    { method: "GET", path: "/api/v1/guide/{id}" },
    { method: "POST", path: "/api/v1/guide/{id}/stream" },
    { method: "DELETE", path: "/api/v1/guide" },
  ]);
  assert.equal(matchesRoute("GET /api/v1/guide/<guide id>", routes), true);
  assert.equal(matchesRoute("POST /api/v1/guide/7/stream?x=1", routes), true);
  assert.equal(matchesRoute("PUT /api/v1/guide/7", routes), false);
  assert.equal(matchesRoute("/api/v1/guide/", routes), true);
});

test("멤버는 선언과 클래스 안 호출로 찾고 다른 객체의 멤버 호출은 세지 않는다", () => {
  const java = "class Guide {\n  private final int limit;\n  enum Kind { FAST, SLOW }\n  void send() { items.stream().map(Other::wrap); refresh(); }\n}";
  for (const member of ["limit", "FAST", "SLOW", "send", "refresh", "Kind"]) assert.equal(declaresMember(java, member), true, member);
  for (const member of ["stream", "wrap", "map"]) assert.equal(declaresMember(java, member), false, member);
});

test("커넥터 문서의 API 경로는 그 커넥터 소스의 문자열과 맞춘다", () => {
  assert.equal(connectorOf("hermes/connectors/guide/src/client.ts"), "hermes/connectors/guide");
  assert.equal(connectorOf("hermes/connectors/README.md"), undefined);
  const routes = stringRoutes('request("/api/v1/accounts"); request(`/api/v1/stocks/${code}/price?x=1`);');
  assert.equal(matchesRoute("GET /api/v1/stocks/005930/price", routes), true);
  assert.equal(matchesRoute("GET /api/v1/orders", routes), false);
});

test("YAML 은 들여쓰기로 펼치고 목록과 블록 문자열 안은 키로 읽지 않는다", () => {
  const yaml = "guide:\n  chat:\n    # 주석\n    ttl: 10m\n    list:\n      - a: 1\n    text: |\n      inner: x\n  other: 1\n";
  assert.deepEqual([...flattenYaml(yaml)], ["guide", "guide.chat", "guide.chat.ttl", "guide.chat.list", "guide.chat.text", "guide.other"]);
});

test("검사 대상은 docs 바로 아래 문서와 코드 옆 README 이고 ADR 과 tasks 는 뺀다", () => {
  assert.equal(isTargetDocument("backend/docs/flow.md"), true);
  assert.equal(isTargetDocument("docs/features/chat.md"), true);
  assert.equal(isTargetDocument("hermes/plugins/fos-ctx/README.md"), true);
  assert.equal(isTargetDocument("README.md"), false);
  assert.equal(isTargetDocument("backend/docs/adr/INDEX.md"), false);
  assert.equal(isTargetDocument("tasks/README.md"), false);
});
