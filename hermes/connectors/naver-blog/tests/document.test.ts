import { expect, test } from "bun:test";
import { DocumentShapeError, documentToDraft, EXISTING_LINE } from "../src/document.ts";

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
