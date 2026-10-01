import assert from "node:assert/strict";
import { test } from "node:test";
import { normalizeMarkdown } from "../../web/src/components/chat/normalize-markdown.ts";

test("앞뒤 파이프 유무와 관계없이 빠진 구분 줄을 한 번 보완한다", () => {
  for (const edge of ["", "|"]) {
    const rows = ["번호 | 구분 | 금액", "1 | 식비 | 100", "2 | 교통 | 200", "3 | 기타 | 300"];
    const source = rows.map((row) => `${edge}${row}${edge}`).join("\n");
    const expected = source.split("\n");
    expected.splice(1, 0, "| --- | --- | --- |");
    assert.equal(normalizeMarkdown(source), expected.join("\n"));
    assert.equal(normalizeMarkdown(normalizeMarkdown(source)), normalizeMarkdown(source));
  }
});

test("기존 표와 한 줄 문장과 열 수가 다른 행은 보존한다", () => {
  for (const source of [
    "| 항목 | 값 |\n| :--- | ---: |\n| 하나 | 둘 |\n| 셋 | 넷 |",
    "선택은 A | B 중 하나다.",
    "A | B | C\n하나 | 둘",
    "A \\| B\nC \\| D",
  ]) {
    assert.equal(normalizeMarkdown(source), source);
  }
});

test("펜스의 종류와 길이와 들여쓴 코드를 보존한다", () => {
  for (const source of [
    "```text\nA | B\nC | D\n```",
    "~~~\nA | B\nC | D\n~~~",
    "````\n```\nA | B\nC | D\n````",
    "~~~\n```\nA | B\nC | D\n~~~",
    "    A | B\n    C | D",
    "\tA | B\n\tC | D",
    "```\nA | B\nC | D",
    "> ```\n> A | B\n> C | D\n> ```",
    ">     A | B\n>     C | D",
    "- ```\n  A | B\n  C | D\n  ```",
  ]) {
    assert.equal(normalizeMarkdown(source), source);
  }
});

test("이스케이프한 파이프는 열로 세지 않고 CRLF를 보존한다", () => {
  assert.equal(
    normalizeMarkdown("| A \\| B | C |\r\n| D | E |\r\n"),
    "| A \\| B | C |\r\n| --- | --- |\r\n| D | E |\r\n",
  );
});

test("인용문 표에는 인용 접두사를 붙이고 닫지 않은 컨테이너 코드는 밖에서 끝난다", () => {
  assert.equal(normalizeMarkdown("> A | B\n> C | D"), "> A | B\n> | --- | --- |\n> C | D");
  for (const code of ["> ```\n> A | B\n> C | D", "- ```\n  A | B\n  C | D"]) {
    assert.equal(normalizeMarkdown(code + "\n\nH | I\nJ | K"), code + "\n\nH | I\n| --- | --- |\nJ | K");
  }
});

test("첫 두 행이 확인된 뒤 스트림의 미완성 행이 붙어도 표 판정을 유지한다", () => {
  const prefix = "번호 | 구분 | 금액\n1 | 식비 | 100\n";
  assert.equal(normalizeMarkdown("번호 | 구분 | 금액\n1"), "번호 | 구분 | 금액\n1");
  for (const suffix of ["2", "2 |", "2 | 교통", "2 | 교통 |", "2 | 교통 | 200"]) {
    assert.ok(normalizeMarkdown(prefix + suffix).startsWith("번호 | 구분 | 금액\n| --- | --- | --- |\n1 | 식비 | 100\n"));
    assert.equal(normalizeMarkdown(prefix + suffix).match(/\| --- \| --- \| --- \|/g)?.length, 1);
  }
});
