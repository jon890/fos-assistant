import { expect, test } from "bun:test";
import {
  DocumentShapeError,
  documentToDraft,
  draftToDocument,
  type EditorDocument,
  EXISTING_LINE,
} from "../src/document.ts";

const paragraph = (...values: string[]) => ({
  nodes: values.map((value) => ({ value, "@ctype": "textNode" })),
  "@ctype": "paragraph",
});

test("글 문단은 한 줄씩, 그 밖의 구성요소는 종류마다 번호를 단 기존 구성요소 줄이 된다", () => {
  const result = documentToDraft({
    documentId: "1",
    document: {
      components: [
        { "@ctype": "documentTitle", title: [paragraph("가상", "국수")] },
        { "@ctype": "text", value: [paragraph("첫 줄"), paragraph("둘", "째\n줄"), paragraph()] },
        { "@ctype": "image" },
        { "@ctype": "image" },
        { "@ctype": "sticker" },
        { "@ctype": "placesMap" },
        { "@ctype": "horizontalLine" },
        { "@ctype": "bad ctype!" },
      ],
    },
  });

  expect(result.title).toBe("가상국수");
  expect(result.body.split("\n")).toEqual([
    "첫 줄",
    "둘째 줄",
    "",
    "[기존 사진 1]",
    "[기존 사진 2]",
    "[기존 스티커 1]",
    "[기존 지도 1]",
    "[기존 구성요소 1: horizontalLine]",
    "[기존 구성요소 2]",
  ]);
  expect(result.existing.map((item) => item.index)).toEqual([2, 3, 4, 5, 6, 7]);
  for (const item of result.existing) expect(EXISTING_LINE.test(item.line)).toBe(true);
});

test.each([
  ["components 가 없는 문서", { document: {} }],
  ["제목이 없는 문서", { document: { components: [] } }],
  [
    "value 가 없는 글 구성요소",
    { document: { components: [{ "@ctype": "documentTitle", title: [] }, { "@ctype": "text" }] } },
  ],
  [
    "nodes 가 없는 문단",
    { document: { components: [{ "@ctype": "documentTitle", title: [{}] }] } },
  ],
])("%s 는 DocumentShapeError 다", (_, data) => {
  expect(() => documentToDraft(data as never)).toThrow(DocumentShapeError);
});

const NEW_ID = /^SE-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** 꾸밈 칸을 넣은 문단. 다시 쓴 문단이 원래 객체를 그대로 옮겼는지 본다. */
const styled = (id: string, value: string) => ({
  id,
  nodes: [
    { id: `${id}-node`, value, style: { fontSizeCode: "fs19", bold: true }, "@ctype": "textNode" },
  ],
  "@ctype": "paragraph",
});

/** 새로 만든 문단. 꾸밈 칸이 없고 id 는 새로 만든 모양이다. */
const fresh = (value: string) => ({
  id: expect.stringMatching(NEW_ID),
  nodes: [{ id: expect.stringMatching(NEW_ID), value, "@ctype": "textNode" }],
  "@ctype": "paragraph",
});

/** 제목 구성요소. 원래 문서와 고친 문서가 같은 id 를 쓴다. */
const titleComponent = (value: string) => ({
  id: "SE-title",
  layout: "default",
  title: [
    {
      id: "SE-title-p",
      nodes: [{ id: "SE-title-n", value, "@ctype": "textNode" }],
      "@ctype": "paragraph",
    },
  ],
  "@ctype": "documentTitle",
});

/**
 * 글 셋, 사진 둘, 스티커 하나인 문서.
 * 원래 본문은 `첫 줄`, `둘째 줄`, 사진 1, 사진 2, 스티커 1, `셋째 줄` 이다.
 */
const sample = (): EditorDocument =>
  ({
    documentId: "1",
    document: {
      id: "SE-doc",
      version: "2.8.0",
      theme: "default",
      components: [
        titleComponent("가상 국수"),
        {
          id: "SE-t1",
          layout: "default",
          value: [styled("SE-p1", "첫 줄"), styled("SE-p2", "둘째 줄")],
          "@ctype": "text",
        },
        { id: "SE-a", src: "https://example.com/a.jpg", "@ctype": "image" },
        { id: "SE-b", src: "https://example.com/b.jpg", "@ctype": "image" },
        { id: "SE-s", packCode: "가상", "@ctype": "sticker" },
        { id: "SE-t2", layout: "default", value: [styled("SE-p3", "셋째 줄")], "@ctype": "text" },
      ],
    },
  }) as EditorDocument;

const components = (data: EditorDocument) => data.document!.components!;
/** 글이 아닌 구성요소의 id 차례. */
const existingIds = (data: EditorDocument) =>
  components(data)
    .filter((component) => component["@ctype"] !== "documentTitle" && component["@ctype"] !== "text")
    .map((component) => component.id);

/** 비교 표 상한을 넘고 같은 글자가 반복되는 문서. 사진 앞뒤 문단의 id 도 서로 다르다. */
const longDocument = () => ({
  document: { components: [
    titleComponent("원본"),
    { "@ctype": "text", value: Array.from({ length: 1000 }, (_, i) => styled(`앞-${i}`, i === 999 ? "끝" : "가")) },
    { id: "사진", "@ctype": "image", src: "https://example.com/image.jpg" },
    { "@ctype": "text", value: Array.from({ length: 1000 }, (_, i) => styled(`뒤-${i}`, "가")) },
  ] },
});

test("2,000개 중복 문단의 제목만 고쳐도 문단 id, 꾸밈과 사진을 보존한다", () => {
  const original = longDocument();
  const result = draftToDocument(original, "수정", documentToDraft(original).body);
  expect(components(result).map(c => c["@ctype"])).toEqual(components(original).map(c => c["@ctype"]));
  expect(components(result)[1]!.value).toEqual(components(original)[1]!.value);
  expect(components(result)[3]!.value).toEqual(components(original)[3]!.value);
  expect(components(result)[2]).toEqual(components(original)[2]);
});

test.each(["교체", "삽입", "삭제"])("긴 본문 가운데 한 줄 %s 시 앞뒤 중복 문단과 사진을 보존한다", (change) => {
  const original = longDocument();
  const before = documentToDraft(original).body.split("\n");
  before.splice(999, change === "삽입" ? 0 : 1, ...(change === "삭제" ? [] : ["수정"]));
  const result = draftToDocument(original, "원본", before.join("\n"));
  const paragraphs = components(result).filter(c => c["@ctype"] === "text").flatMap(c => c.value!);
  expect(paragraphs.slice(0, 999)).toEqual(components(original)[1]!.value!.slice(0, 999));
  expect(paragraphs.slice(-1000)).toEqual(components(original)[3]!.value!);
  expect(components(result).find(c => c["@ctype"] === "image")).toEqual(components(original)[2]);
  expect(documentToDraft(result).body).toBe(before.join("\n"));
});

test("앞뒤를 뺀 비교 구간도 상한을 넘으면 원본 꾸밈을 버리는 대신 거절한다", () => {
  const original = longDocument();
  const copy = structuredClone(original);
  const after = Array.from({ length: 2000 }, (_, i) => i === 1000 ? "가" : "나").join("\n");
  expect(() => draftToDocument(original, "원본", after)).toThrow("문단의 꾸밈을 안전하게 보존할 수 없습니다");
  expect(original).toEqual(copy);
});

test("고친 글로 만든 문서는 기존 구성요소를 본문 차례로 옮기고 바뀌지 않은 문단은 원래 객체를 쓴다", () => {
  const original = sample();
  const copy = structuredClone(original);

  const result = draftToDocument(
    original,
    "가상 칼국수",
    "첫 줄\n[기존 사진 2]\n둘째 줄을 고쳤다\n[기존 사진 1]\n셋째 줄",
  );

  expect(original).toEqual(copy);
  const list = components(result);
  expect(list.map((component) => component["@ctype"])).toEqual([
    "documentTitle",
    "text",
    "image",
    "text",
    "image",
    "text",
  ]);
  expect(existingIds(result)).toEqual(["SE-b", "SE-a"]);
  expect(list[2]).toEqual(components(copy)[3]!);
  expect(list[4]).toEqual(components(copy)[2]!);

  const reread = documentToDraft(result);
  expect(reread.title).toBe("가상 칼국수");
  expect(reread.body.split("\n")).toEqual([
    "첫 줄",
    "[기존 사진 1]",
    "둘째 줄을 고쳤다",
    "[기존 사진 2]",
    "셋째 줄",
  ]);

  // 바뀌지 않은 문단은 id 와 꾸밈까지 원래와 같고, 고친 문단은 새 id 의 꾸밈 없는 문단이다.
  expect(list[1]!.value).toEqual([styled("SE-p1", "첫 줄")]);
  expect(list[5]!.value).toEqual([styled("SE-p3", "셋째 줄")]);
  expect(list[3]!.value).toEqual([fresh("둘째 줄을 고쳤다")]);
  for (const index of [1, 3, 5]) {
    expect(list[index]!.id).toMatch(NEW_ID);
    expect(list[index]!.layout).toBe("default");
  }

  // 제목은 원래 구성요소와 첫 문단, 첫 노드의 id 를 지키고 루트 칸은 그대로다.
  expect(list[0]).toEqual(titleComponent("가상 칼국수"));
  expect({ ...result, document: { ...result.document, components: [] } }).toEqual({
    ...copy,
    document: { ...copy.document, components: [] },
  });
});

test("앞 사진을 빼면 뒤 사진만 남고 다시 읽으면 그 사진이 1 번이다", () => {
  const result = draftToDocument(
    sample(),
    "가상 국수",
    "첫 줄\r\n둘째 줄\r\n[기존 사진 2]\r\n[기존 스티커 1]\r\n셋째 줄",
  );

  expect(existingIds(result)).toEqual(["SE-b", "SE-s"]);
  expect(documentToDraft(result).body.split("\n")).toEqual([
    "첫 줄",
    "둘째 줄",
    "[기존 사진 1]",
    "[기존 스티커 1]",
    "셋째 줄",
  ]);
  expect(components(result)[1]!.value).toEqual([
    styled("SE-p1", "첫 줄"),
    styled("SE-p2", "둘째 줄"),
  ]);
});

test.each([
  ["원래 글에 없는 기존 구성요소 줄", "첫 줄\n[기존 지도 1]"],
  ["두 번 쓴 기존 구성요소 줄", "[기존 사진 1]\n첫 줄\n[기존 사진 1]"],
])("%s 는 DocumentShapeError 다", (_, body) => {
  expect(() => draftToDocument(sample(), "가상 국수", body)).toThrow(DocumentShapeError);
});

test("빈 본문은 빈 문단 하나의 글 구성요소가 된다", () => {
  const result = draftToDocument(sample(), "가상 국수", "");

  const list = components(result);
  expect(list.map((component) => component["@ctype"])).toEqual(["documentTitle", "text"]);
  expect(list[1]!.value).toEqual([fresh("")]);
  expect(documentToDraft(result).body).toBe("");
});

test("기존 구성요소 줄만 남은 본문은 끝에 빈 글 구성요소를 더해 다시 읽으면 빈 줄이 붙는다", () => {
  const result = draftToDocument(sample(), "가상 국수", "[기존 스티커 1]");

  expect(components(result).map((component) => component.id)).toEqual([
    "SE-title",
    "SE-s",
    expect.stringMatching(NEW_ID),
  ]);
  expect(components(result)[2]!.value).toEqual([fresh("")]);
  expect(documentToDraft(result).body).toBe("[기존 스티커 1]\n");
});
