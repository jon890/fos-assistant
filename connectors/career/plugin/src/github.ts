import { z } from "zod";
import { CareerError, type CareerErrorCode, type FetchLike } from "./errors.ts";

const apiBase = "https://api.github.com";
const readmePath = "README.md";

const repoSchema = z.object({ default_branch: z.string().min(1) });
const refSchema = z.object({ object: z.object({ sha: z.string().min(1) }) });
const commitSchema = z.object({ tree: z.object({ sha: z.string().min(1) }) });
const shaSchema = z.object({ sha: z.string().min(1) });
const contentSchema = z.object({ content: z.string(), encoding: z.literal("base64") });

type Step = {
  method: "GET" | "POST" | "PATCH";
  path: string;
  body?: unknown;
  // 브랜치를 옮길 때만 409와 422를 다른 커밋이 먼저 올라온 것으로 본다.
  movesBranch?: boolean;
  // 파일 조회에서만 404를 파일 없음으로 본다.
  missingIsAbsent?: boolean;
};

function statusCode(status: number, movesBranch: boolean): CareerErrorCode {
  if (status === 401) return "CAREER_GITHUB_UNAUTHORIZED";
  if (status === 409 || status === 422) return movesBranch ? "CAREER_GITHUB_CONFLICT" : "CAREER_GITHUB_UNAVAILABLE";
  if (status === 429 || status >= 500) return "CAREER_GITHUB_UNAVAILABLE";
  return "CAREER_GITHUB_FORBIDDEN";
}

function decodeBase64Utf8(content: string): string {
  try {
    const binary = atob(content.replace(/\s/g, ""));
    const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new CareerError("CAREER_INVALID_RESPONSE");
  }
}

function refPath(branch: string): string {
  return `heads/${branch.split("/").map(encodeURIComponent).join("/")}`;
}

/** 프로필 저장소의 README.md를 읽고 갱신한다. */
export class GithubProfileRepo {
  private readonly token: () => Promise<string>;
  private readonly repoPath: string;

  /** 직접 입력한 토큰이나 최신 토큰을 돌려주는 함수를 받는다. 한 작업의 요청들은 같은 토큰을 쓴다. */
  constructor(
    private readonly config: { token: string | (() => Promise<string>); repo: string },
    private readonly fetchImpl: FetchLike = fetch,
  ) {
    const source = config.token;
    this.token = typeof source === "string" ? async () => source.trim() : source;
    const [owner = "", name = ""] = config.repo.split("/");
    this.repoPath = `/repos/${encodeURIComponent(owner)}/${encodeURIComponent(name)}`;
  }

  async check(): Promise<void> {
    const token = await this.token();
    await this.defaultBranch(token);
  }

  /** 먼저 읽은 브랜치 끝 커밋으로 README를 조회해 검토할 내용과 커밋을 맞춘다. */
  async read(): Promise<{
    repo: string;
    branch: string;
    head: string;
    readme: string | null;
  }> {
    const token = await this.token();
    const branch = await this.defaultBranch(token);
    const head = await this.headOf(token, branch);
    const readme = await this.send(token, this.contentsStep(readmePath, head));
    return {
      repo: this.config.repo,
      branch,
      head,
      readme: readme === null ? null : decodeBase64Utf8(this.parse(contentSchema, readme).content),
    };
  }

  /** 검토한 브랜치와 커밋이 그대로일 때만 README를 커밋한다. 브랜치는 강제로 옮기지 않는다. */
  async commitProfile(
    readme: string,
    message: string,
    expected: { branch: string; head: string },
  ): Promise<{ changed: boolean; commitSha: string; branch: string }> {
    const token = await this.token();
    const branch = await this.defaultBranch(token);
    if (branch !== expected.branch) throw new CareerError("CAREER_GITHUB_STALE_REVIEW");
    const head = await this.headOf(token, branch);
    if (head !== expected.head) throw new CareerError("CAREER_GITHUB_STALE_REVIEW");
    const ref = refPath(branch);
    const baseTree = this.parse(commitSchema, await this.send(token, { method: "GET", path: `/git/commits/${head}` }))
      .tree.sha;
    const readmeBlob = await this.createSha(token, "/git/blobs", { content: readme, encoding: "utf-8" });
    const tree = await this.createSha(token, "/git/trees", {
      base_tree: baseTree,
      tree: [{ path: readmePath, mode: "100644", type: "blob", sha: readmeBlob }],
    });
    // 이미 같은 내용이면 빈 커밋을 만들지 않는다.
    if (tree === baseTree) return { changed: false, commitSha: head, branch };
    const commit = await this.createSha(token, "/git/commits", { message, tree, parents: [head] });
    await this.send(token, { method: "PATCH", path: `/git/refs/${ref}`, body: { sha: commit, force: false }, movesBranch: true });
    return { changed: true, commitSha: commit, branch };
  }

  private async defaultBranch(token: string): Promise<string> {
    return this.parse(repoSchema, await this.send(token, { method: "GET", path: "" })).default_branch;
  }

  private async headOf(token: string, branch: string): Promise<string> {
    return this.parse(refSchema, await this.send(token, { method: "GET", path: `/git/ref/${refPath(branch)}` })).object.sha;
  }

  private contentsStep(file: string, commitSha: string): Step {
    return { method: "GET", path: `/contents/${file}?ref=${encodeURIComponent(commitSha)}`, missingIsAbsent: true };
  }

  private async createSha(token: string, path: string, body: unknown): Promise<string> {
    return this.parse(shaSchema, await this.send(token, { method: "POST", path, body })).sha;
  }

  private parse<T>(schema: z.ZodType<T>, value: unknown): T {
    const parsed = schema.safeParse(value);
    if (!parsed.success) throw new CareerError("CAREER_INVALID_RESPONSE");
    return parsed.data;
  }

  /** JSON 본문을 돌려준다. 파일 조회가 404이면 null이다. */
  private async send(token: string, step: Step): Promise<unknown> {
    const headers: Record<string, string> = {
      Authorization: `Bearer ${token}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "fos-career-connector",
    };
    if (step.body !== undefined) headers["Content-Type"] = "application/json";
    // 쓰기 요청은 재시도하지 않는다. 토큰이 다른 호스트로 가지 않게 리디렉션을 막는다.
    let response: Response;
    try {
      response = await this.fetchImpl(`${apiBase}${this.repoPath}${step.path}`, {
        method: step.method,
        headers,
        redirect: "error",
        signal: AbortSignal.timeout(5_000),
        ...(step.body === undefined ? {} : { body: JSON.stringify(step.body) }),
      });
    } catch {
      throw new CareerError("CAREER_GITHUB_UNAVAILABLE");
    }
    if (step.missingIsAbsent && response.status === 404) return null;
    if (!response.ok) throw new CareerError(statusCode(response.status, step.movesBranch === true));
    if (step.method === "PATCH") return undefined;
    try {
      return await response.json();
    } catch {
      throw new CareerError("CAREER_INVALID_RESPONSE");
    }
  }
}
