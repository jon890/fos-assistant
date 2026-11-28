import { expect, test } from "bun:test";
import {
  fieldsFromResume,
  fingerprint,
  normalizeMonth,
  normalizeText,
  outOfScopeFromResume,
  parseField,
  updateWantedResumeSchema,
} from "./fields.ts";

const api = {
  resume: { about: "백엔드 개발자입니다." },
  careers: [
    {
      company_name: "가나다 주식회사",
      projects: [
        { title: "주문 API 개편", description: "- 주문 흐름을 나눴다" },
        { title: "", description: "" },
      ],
    },
    { projects: [{ title: "정산 배치", description: "- 정산을 옮겼다" }] },
  ],
  skills: [{ name: "Java" }, "Kotlin", { id: 3 }],
  links: [{ url: "https://example.com" }],
};

test("정규화는 줄바꿈 모양과 앞뒤 공백만 맞춘다", () => {
  expect(normalizeText("  a\r\nb\rc  ")).toBe("a\nb\nc");
  expect(fingerprint("a\r\nb")).toBe(fingerprint(" a\nb "));
  expect(fingerprint("a b")).not.toBe(fingerprint("a  b"));
  expect(fingerprint("x")).toMatch(/^[0-9a-f]{16}$/);
  expect(normalizeMonth("2026.1")).toBe("2026-01");
  expect(normalizeMonth("2026.11")).toBe("2026-11");
  expect(normalizeMonth("2026-03")).toBe("2026-03");
  for (const bad of ["2026/01", "2026.13", "26.01", "", "2026.0"]) expect(normalizeMonth(bad)).toBeNull();
});

test("칸 목록은 API 차례의 id 와 회사·제목으로 지은 이름을 쓴다", () => {
  const periods = [
    { c: 0, p: 0, start: "2025-01", end: "2026-10" },
    { c: 0, p: 1, start: "", end: "" },
    null,
  ];
  const { fields, unreadable } = fieldsFromResume(api, periods);
  const byId = Object.fromEntries(fields.map((f) => [f.field, f]));
  expect(byId.about).toMatchObject({ label: "간단 소개", value: "백엔드 개발자입니다." });
  expect(byId["project:0:0:description"]).toMatchObject({
    label: "가나다 주식회사 / 주문 API 개편 / 본문",
    value: "- 주문 흐름을 나눴다",
    fingerprint: fingerprint("- 주문 흐름을 나눴다"),
  });
  expect(byId["project:0:0:end"]!.value).toBe("2026-10");
  expect(byId["project:0:1:title"]!.label).toBe("가나다 주식회사 / 빈 항목 / 제목");
  // 회사 키가 없으면 그 경력의 직책을 대신 쓴다.
  expect(byId["project:1:0:title"]!.label).toBe("경력 2 / 정산 배치 / 제목");
  // 칸 묶음을 찾지 못하면 기간은 빈 값이 아니라 읽지 못한 값이다.
  expect(byId["project:1:0:start"]).toBeUndefined();
  expect(unreadable).toEqual(["경력 2 / 정산 배치 / 시작월", "경력 2 / 정산 배치 / 종료월"]);
});

test("범위 밖 칸은 읽을 수 있는 글만 모은다", () => {
  expect(outOfScopeFromResume(api, [{ content: "에이전트로 리뷰" }, 7])).toEqual({
    skills: ["Java", "Kotlin"],
    links: ["https://example.com"],
    aiCompetencies: ["에이전트로 리뷰"],
  });
  expect(outOfScopeFromResume({}, null)).toEqual({ skills: [], links: [], aiCompetencies: [] });
});

test("칸 id 는 소개와 프로젝트 네 칸만 받는다", () => {
  expect(parseField("about")).toEqual({ kind: "about" });
  expect(parseField("project:1:2:end")).toEqual({ kind: "project", c: 1, p: 2, part: "end" });
  for (const bad of ["skills", "links", "project:1:2:skills", "project:a:0:title", "about "]) expect(parseField(bad)).toBeNull();
});

const change = (field: string, value: string) => ({ field, label: "이름", before: "0123456789abcdef", value });
const args = (changes: unknown[]) => ({ resumeKey: "KEY=", resumeTitle: "기본 이력서", changes });

test("쓰기 인자는 범위 밖 칸, 중복, 잘못된 값, 16,000바이트 초과를 거절한다", () => {
  expect(updateWantedResumeSchema.safeParse(args([change("about", "새 소개"), change("project:0:0:end", "2026.11")])).success).toBe(true);
  expect(updateWantedResumeSchema.safeParse(args([change("skills", "Java")])).success).toBe(false);
  expect(updateWantedResumeSchema.safeParse(args([change("about", "a"), change("about", "b")])).success).toBe(false);
  expect(updateWantedResumeSchema.safeParse(args([change("project:0:0:start", "2026/01")])).success).toBe(false);
  expect(updateWantedResumeSchema.safeParse(args([change("project:0:0:description", "  \n ")])).success).toBe(false);
  expect(updateWantedResumeSchema.safeParse(args([{ ...change("about", "x"), before: "nothex" }])).success).toBe(false);
  expect(updateWantedResumeSchema.safeParse(args([])).success).toBe(false);
  // 약 2,000자인 한국어 본문 세 개를 합치면 16,000바이트를 넘는다.
  const big = "가".repeat(2_000);
  const three = [0, 1, 2].map((p) => change(`project:0:${p}:description`, big));
  expect(updateWantedResumeSchema.safeParse(args(three)).success).toBe(false);
  expect(updateWantedResumeSchema.safeParse(args(three.slice(0, 2))).success).toBe(true);
});

test("쓰기 인자는 resumeKey 와 resumeTitle 이 필수이고 키는 경로 한 마디만 받는다", () => {
  const one = [change("about", "새 소개")];
  expect(updateWantedResumeSchema.safeParse({ resumeTitle: "t", changes: one }).success).toBe(false);
  expect(updateWantedResumeSchema.safeParse({ resumeKey: "K", changes: one }).success).toBe(false);
  for (const bad of ["../x", "a/b", "a?b", "a#b", "a b"])
    expect(updateWantedResumeSchema.safeParse({ resumeKey: bad, resumeTitle: "t", changes: one }).success).toBe(false);
  expect(updateWantedResumeSchema.safeParse({ resumeKey: "AbC=_-1", resumeTitle: "t", changes: one }).success).toBe(true);
});
