import { z } from "zod";
import { CareerError, safeError } from "./errors.ts";
import type { GithubProfileRepo } from "./github.ts";
import { withToolDescriptions } from "./tool-metadata.ts";
import {
  getWantedResumeSchema,
  updateWantedResumeSchema,
  type GetWantedResumeArgs,
  type UpdateWantedResumeArgs,
} from "./wanted/fields.ts";
import { WantedTools } from "./wanted/tools.ts";

const maxBodyBytes = 65_536;
const nonBlankWithinLimit = z
  .string()
  .refine((value) => value.trim().length > 0)
  .refine((value) => new TextEncoder().encode(value).length <= maxBodyBytes);

type UpdateGithubProfileArgs = { readme: string; expectedBranch: string; expectedHead: string };

// README 만 받는다. 사용량 차트와 Tokens 배지는 사용량 기록이 노트북 파일로 옮겨 가며 프로필에서 빠졌으므로
// strictObject 로 months, chart 와 그 밖의 키를 거절한다.
const updateGithubProfileSchema = z.strictObject({
  readme: nonBlankWithinLimit,
  // get_github_profile 이 돌려준 branch 와 head 다. 저장된 승인 인자에 함께 들어 있어서
  // 나중에 실행한 승인도 사용자가 검토한 시점을 가리킨다.
  expectedBranch: z.string().min(1).max(255),
  expectedHead: z.string().regex(/^[0-9a-f]{40}$/),
});

// 여기 도구는 모두 바깥 사이트(GitHub, 원티드)에 닿는다. 커리어 자료는 이 서버가 닿지 않는 에이전트 작업 폴더에 있어서
// 그 작업은 스킬과 로컬 실행기가 맡는다.
export const toolDefinitions: Record<string, { description: string; schema: z.ZodType }> =
  withToolDescriptions({
    check_connection: {
      schema: z.strictObject({}),
    },
    get_github_profile: {
      schema: z.strictObject({}),
    },
    update_github_profile: {
      schema: updateGithubProfileSchema,
    },
    get_wanted_resume: {
      schema: getWantedResumeSchema,
    },
    update_wanted_resume: {
      schema: updateWantedResumeSchema,
    },
  });

type ToolResult = {
  content: [{ type: "text"; text: string }];
  isError?: true;
  structuredContent?: Record<string, unknown>;
};

export class CareerTools {
  constructor(
    private readonly github?: GithubProfileRepo,
    // 중계 주소가 없으면 두 원티드 도구가 CAREER_BROWSER_UNREACHABLE 로 답한다.
    private readonly wanted: WantedTools = new WantedTools(undefined),
  ) {}

  async call(name: string, raw: unknown): Promise<ToolResult> {
    try {
      if (!Object.hasOwn(toolDefinitions, name)) throw new CareerError("CAREER_UNKNOWN_TOOL");
      const parsed = toolDefinitions[name]!.schema.safeParse(raw);
      if (!parsed.success) throw new CareerError("CAREER_INVALID_INPUT");
      switch (name) {
        case "check_connection": {
          // GitHub 은 선택이라 토큰이 없어도 연결은 정상이다.
          await this.github?.check();
          const result = { github: this.github ? "ok" : "not_configured" };
          return { ...this.success(result), structuredContent: result };
        }
        case "get_github_profile":
          return this.success(await this.requireGithub().read());
        case "update_github_profile": {
          const { readme, expectedBranch, expectedHead } = parsed.data as UpdateGithubProfileArgs;
          return this.success(
            await this.requireGithub().commitProfile(readme, "docs: 프로필 README 를 갱신한다", {
              branch: expectedBranch,
              head: expectedHead,
            }),
          );
        }
        // 화면 넘기기와 멈춘 실행은 isError 가 아닌 정상 결과다(ADR-20261111 / connector-handoff, ADR-092).
        case "get_wanted_resume":
          return this.success(await this.wanted.getResume(parsed.data as GetWantedResumeArgs));
        case "update_wanted_resume":
          return this.success(await this.wanted.updateResume(parsed.data as UpdateWantedResumeArgs));
      }
      throw new CareerError("CAREER_UNKNOWN_TOOL");
    } catch (error) {
      const extra = error instanceof CareerError ? (error.details ?? {}) : {};
      return {
        isError: true,
        content: [{ type: "text", text: JSON.stringify({ error: safeError(error), ...extra }) }],
      };
    }
  }

  private requireGithub(): GithubProfileRepo {
    if (!this.github) throw new CareerError("CAREER_GITHUB_NOT_CONFIGURED");
    return this.github;
  }

  private success(value: unknown): ToolResult {
    return { content: [{ type: "text", text: JSON.stringify(value) }] };
  }
}
