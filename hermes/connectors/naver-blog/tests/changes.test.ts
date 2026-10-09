import { expect, test } from "bun:test";
import { draftRevision, type DraftContent } from "../src/changes.ts";

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
