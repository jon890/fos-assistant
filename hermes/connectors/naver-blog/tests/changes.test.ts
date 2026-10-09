import { expect, test } from "bun:test";
import { draftChanges, draftRevision, type DraftContent } from "../src/changes.ts";

const base: DraftContent = {
  title: "동네 국숫집",
  category: "맛집",
  tags: ["국수", "동네"],
  body: ["안녕하세요", "오늘은 국숫집", "", "[기존 사진 1]", "간판이 정겹다", "끝"].join("\n"),
};

test("지문은 같은 글이면 같고 한 글자만 달라도 다르다", () => {
  expect(draftRevision(base)).toBe(draftRevision({ ...base, tags: [...base.tags] }));
  expect(draftRevision(base)).toMatch(/^[0-9a-f]{16}$/);
  expect(draftRevision({ ...base, body: `${base.body}.` })).not.toBe(draftRevision(base));
  expect(draftRevision({ ...base, body: base.body.replaceAll("\n", "\r\n") })).toBe(
    draftRevision(base),
  );
});

test("같은 글이면 바뀌는 내용이 빈 문자열이다", () => {
  expect(draftChanges(base, base)).toBe("");
});

test("제목, 카테고리, 태그와 바뀐 본문 줄을 앞뒤 한 줄과 함께 적는다", () => {
  const after = {
    title: "동네 국숫집 후기",
    category: "일상",
    tags: ["국수", "점심"],
    body: base.body.replace("간판이 정겹다", "간판부터 정겹다"),
  };

  expect(draftChanges(base, after)).toBe(
    [
      "제목: 동네 국숫집 → 동네 국숫집 후기",
      "카테고리: 맛집 → 일상",
      "뺀 태그: 동네",
      "더한 태그: 점심",
      "본문:",
      "  [기존 사진 1]",
      "- 간판이 정겹다",
      "+ 간판부터 정겹다",
      "  끝",
    ].join("\n"),
  );
});

test("떨어진 두 자리가 바뀌면 사이를 … 로 줄인다", () => {
  const after = { ...base, body: base.body.replace("안녕하세요", "반가워요").replace("끝", "마침") };

  expect(draftChanges(base, after).split("\n")).toEqual([
    "본문:",
    "- 안녕하세요",
    "+ 반가워요",
    "  오늘은 국숫집",
    "…",
    "  간판이 정겹다",
    "- 끝",
    "+ 마침",
  ]);
});
