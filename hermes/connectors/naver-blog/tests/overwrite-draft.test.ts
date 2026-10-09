import { expect, test } from "bun:test";
import { validateDraft } from "../src/draft.ts";
import { validateOverwrite, type OverwriteContent } from "../src/overwrite-draft.ts";

const base: OverwriteContent = {
  title: "동네 국숫집",
  category: "맛집",
  tags: ["국수", "동네"],
  body: [
    "안녕하세요",
    "[기존 사진 1]",
    "간판이 정겹다",
    "[기존 사진 2]",
    "[기존 지도 1]",
    "끝",
  ].join("\n"),
};

const edited = (extra: Partial<OverwriteContent> = {}): OverwriteContent => ({
  ...base,
  tags: ["국수", "점심"],
  body: [
    "안녕하세요",
    "[기존 사진 2]",
    "간판부터 정겹다",
    "[기존 사진 1]",
    "[기존 지도 1]",
    "끝",
  ].join("\n"),
  ...extra,
});

const NEW_COMPONENT =
  "덮어쓰기에는 새 사진, 스티커, 지도를 넣지 않습니다. 새 글로 저장하거나 네이버에서 직접 넣어 주세요.";

test("본문 한 줄과 태그 하나를 고치고 기존 사진의 차례를 바꾼 글은 문장이 없다", () => {
  expect(validateOverwrite(edited(), base)).toEqual([]);
});

test("줄 끝 \\r 이 있는 기존 구성요소 줄도 원래 글의 줄과 같다", () => {
  const body = edited().body.replaceAll("\n", "\r\n");

  expect(validateOverwrite(edited({ body }), base)).toEqual([]);
});

test("새 사진 줄은 새 구성요소 문장 하나만 내고 사진 디렉터리 문장을 섞지 않는다", () => {
  const body = `${edited().body}\n[사진 1: 101.jpg]`;

  expect(validateOverwrite(edited({ body }), base)).toEqual([NEW_COMPONENT]);
});

test("새 스티커와 지도 줄도 새 구성요소 문장 하나다", () => {
  const body = `${edited().body}\n[스티커: ogq_abc123-4]\n[지도: 가상국수 | 서울특별시 가상구 예시로 1]`;

  expect(validateOverwrite(edited({ body }), base)).toEqual([NEW_COMPONENT]);
});

test("앞에 공백이 있는 태그는 공백 문장을 낸다", () => {
  expect(validateOverwrite(edited({ tags: ["국수", " 저녁"] }), base)).toEqual([
    "카테고리와 태그 앞뒤에 공백을 두지 않습니다.",
  ]);
});

test("뒤에 공백이 있는 카테고리도 공백 문장을 낸다", () => {
  expect(validateOverwrite(edited({ category: "맛집 " }), base)).toEqual([
    "카테고리와 태그 앞뒤에 공백을 두지 않습니다.",
  ]);
});

test("같은 기존 구성요소 줄을 두 번 쓰면 한 번만 둔다는 문장을 낸다", () => {
  const body = `${edited().body}\n[기존 사진 1]`;

  expect(validateOverwrite(edited({ body }), base)).toEqual(["[기존 사진 1] 은 한 번만 둡니다."]);
});

test("원래 글에 없는 기존 구성요소 줄은 없다는 문장을 낸다", () => {
  const body = `${edited().body}\n[기존 지도 2]`;

  expect(validateOverwrite(edited({ body }), base)).toEqual(["[기존 지도 2] 은 원래 글에 없습니다."]);
});

test("base 가 없으면 원래 글에 있는지 보지 않는다", () => {
  const body = `${edited().body}\n[기존 지도 2]`;

  expect(validateOverwrite(edited({ body }))).toEqual([]);
});

test("원래 글과 같은 글은 바뀐 것이 없다는 문장을 낸다", () => {
  expect(validateOverwrite({ ...base, tags: [...base.tags] }, base)).toEqual(["바뀐 것이 없습니다."]);
});

test("101자 제목은 새 글 검사와 같은 제목 문장 하나를 낸다", () => {
  const title = "가".repeat(101);
  const expected = validateDraft({ ...edited(), title, body: "" });

  expect(expected).toHaveLength(1);
  expect(validateOverwrite(edited({ title }), base)).toEqual(expected);
});
