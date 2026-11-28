import { createHash } from "node:crypto";
import { z } from "zod";

// 원티드 이력서의 순수 로직이다. 칸 식별자, 정규화와 지문, 도구 입력 검증을 갖는다.
// 칸 식별자는 API 순서 `careers[c].projects[p]` 를 따른다. 화면에서는 순번이 아니라 제목으로 찾는다.

export const textParts = ["title", "description"] as const;
export const periodParts = ["start", "end"] as const;
export type ProjectPart = (typeof textParts)[number] | (typeof periodParts)[number];
export type ParsedField = { kind: "about" } | { kind: "project"; c: number; p: number; part: ProjectPart };

/** CRLF 를 LF 로 바꾸고 앞뒤 공백을 자른다. 비교 전 정규화는 이것뿐이다. */
export function normalizeText(value: string): string {
  return value.replace(/\r\n?/g, "\n").trim();
}

/** "2026.1", "2026.01", "2026-01" 을 "2026-01" 로 바꾼다. 그 밖의 값은 null 이다. */
export function normalizeMonth(value: string): string | null {
  const match = /^(\d{4})[.-](\d{1,2})$/.exec(value.trim());
  if (!match) return null;
  const month = Number(match[2]);
  if (month < 1 || month > 12) return null;
  return `${match[1]}-${String(month).padStart(2, "0")}`;
}

/** 정규화한 값의 SHA-256 앞 16자리 16진수다. */
export function fingerprint(value: string): string {
  return createHash("sha256").update(normalizeText(value)).digest("hex").slice(0, 16);
}

export function parseField(field: string): ParsedField | null {
  if (field === "about") return { kind: "about" };
  const match = /^project:(\d{1,3}):(\d{1,3}):(title|description|start|end)$/.exec(field);
  if (!match) return null;
  return { kind: "project", c: Number(match[1]), p: Number(match[2]), part: match[3] as ProjectPart };
}

export type WantedField = { field: string; label: string; value: string; fingerprint: string };
export type ProjectPeriod = { c: number; p: number; start: string; end: string } | null;

type ApiProject = { title?: unknown; description?: unknown };
type ApiCareer = { company_name?: unknown; company?: { name?: unknown }; projects?: unknown };
export type ResumeApi = { resume?: { about?: unknown; title?: unknown }; careers?: unknown; skills?: unknown; links?: unknown };

const text = (value: unknown) => (typeof value === "string" ? value : "");

export function careersOf(api: ResumeApi): { company: string; projects: { title: string; description: string }[] }[] {
  const careers = Array.isArray(api.careers) ? (api.careers as ApiCareer[]) : [];
  return careers.map((career, c) => ({
    // 회사 키는 문서에 없다. 두 후보가 모두 없으면 그 경력의 직책을 쓴다.
    company: text(career?.company_name) || text(career?.company?.name) || `경력 ${c + 1}`,
    projects: (Array.isArray(career?.projects) ? (career.projects as ApiProject[]) : []).map((project) => ({
      title: text(project?.title),
      description: text(project?.description),
    })),
  }));
}

const partLabel: Record<ProjectPart, string> = { title: "제목", description: "본문", start: "시작월", end: "종료월" };

export function projectLabel(company: string, title: string, part: ProjectPart): string {
  return `${company} / ${normalizeText(title) || "빈 항목"} / ${partLabel[part]}`;
}

function entry(field: string, label: string, value: string): WantedField {
  return { field, label, value, fingerprint: fingerprint(value) };
}

/**
 * 고칠 수 있는 칸이다. `periods` 는 화면이 프로젝트마다 보인 기간을 API 순서로 담는다.
 * null 은 프로젝트 칸 묶음을 찾지 못했다는 뜻이라 그 기간을 빈 값 대신 읽지 못한 값으로 알린다.
 */
export function fieldsFromResume(
  api: ResumeApi,
  periods: ProjectPeriod[],
): { fields: WantedField[]; unreadable: string[] } {
  const fields = [entry("about", "간단 소개", text(api.resume?.about))];
  const unreadable: string[] = [];
  let index = 0;
  careersOf(api).forEach((career, c) =>
    career.projects.forEach((project, p) => {
      const period = periods[index++] ?? null;
      for (const part of textParts)
        fields.push(entry(`project:${c}:${p}:${part}`, projectLabel(career.company, project.title, part), project[part]));
      for (const part of periodParts) {
        const label = projectLabel(career.company, project.title, part);
        if (period) fields.push(entry(`project:${c}:${p}:${part}`, label, period[part]));
        else unreadable.push(label);
      }
    }),
  );
  return { fields, unreadable };
}

/** get_wanted_resume 이 이 이력서의 칸에 주는 label 이다. 그 자리가 없어졌으면 null 이다. */
export function labelOf(api: ResumeApi, field: ParsedField): string | null {
  if (field.kind === "about") return "간단 소개";
  const career = careersOf(api)[field.c];
  const project = career?.projects[field.p];
  return career && project ? projectLabel(career.company, project.title, field.part) : null;
}

/** 기술, 링크, AI 활용 경험은 읽기만 한다. 문자열로 다듬고 알아보지 못한 값은 건너뛴다. */
export function outOfScopeFromResume(
  api: ResumeApi,
  ai: unknown,
): { skills: string[]; links: string[]; aiCompetencies: string[] } {
  const names = (list: unknown, keys: string[]) =>
    (Array.isArray(list) ? list : [])
      .map((item) => {
        if (typeof item === "string") return item;
        for (const key of keys) if (item && typeof item[key] === "string") return item[key] as string;
        return "";
      })
      .filter((value) => value.trim());
  return {
    skills: names(api.skills, ["name", "title", "text"]),
    links: names(api.links, ["url", "link", "title"]),
    aiCompetencies: names(ai, ["content", "description", "text", "title"]),
  };
}

// 이력서 키는 /cv/<key> 의 경로 한 토막이다. 빗금, 점, 쿼리와 프래그먼트 문자는 거절한다.
const resumeKey = z.string().trim().min(1).max(200).regex(/^[^\s/\\?#.]+$/);

export const getWantedResumeSchema = z.strictObject({
  resumeKey: resumeKey.optional(),
});

const ARGUMENT_BYTE_LIMIT = 16_000;

const changeSchema = z
  .strictObject({
    field: z.string().refine((field) => parseField(field) !== null),
    // 회사와 프로젝트 제목은 길 수 있다. 500자는 여유를 두면서 승인 카드를 읽을 만하게 둔다.
    label: z.string().trim().min(1).max(500),
    before: z.string().regex(/^[0-9a-f]{16}$/),
    value: z.string().max(16_000),
  })
  .superRefine((change, context) => {
    const parsed = parseField(change.field);
    if (!parsed) return;
    const period = parsed.kind === "project" && (parsed.part === "start" || parsed.part === "end");
    if (period ? normalizeMonth(change.value) === null : !normalizeText(change.value))
      context.addIssue({ code: "custom", path: ["value"], message: "invalid value" });
  });

export const updateWantedResumeSchema = z
  .strictObject({
    resumeKey,
    resumeTitle: z.string().trim().min(1).max(100),
    changes: z.array(changeSchema).min(1).max(30),
  })
  .superRefine((args, context) => {
    const fields = args.changes.map((change) => change.field);
    if (new Set(fields).size !== fields.length)
      context.addIssue({ code: "custom", path: ["changes"], message: "duplicate field" });
    if (new TextEncoder().encode(JSON.stringify(args)).length > ARGUMENT_BYTE_LIMIT)
      context.addIssue({ code: "custom", path: ["changes"], message: "too large" });
  });

export type UpdateWantedResumeArgs = z.infer<typeof updateWantedResumeSchema>;
export type GetWantedResumeArgs = z.infer<typeof getWantedResumeSchema>;
