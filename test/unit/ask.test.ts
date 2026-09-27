import assert from "node:assert/strict";
import test from "node:test";
import { formatAnswers, parseAsk, splitAnswer } from "../../web/src/lib/ask.ts";

const BLOCK = [
  "<ask>",
  '<question header="식당 이름">어느 식당에 다녀왔어?</question>',
  "<option>행복담</option>",
  '<option description="사진의 간판 글자">행복한 담벼락</option>',
  '<question header="먹은 메뉴" multiple="true">무엇을 먹었어?</question>',
  "<option>국밥</option>",
  "<option>수육</option>",
  "</ask>",
].join("\n");

test("답 끝의 블록은 본문과 카드로 나뉜다", () => {
  const segments = splitAnswer(`사진을 다 봤어.\n\n${BLOCK}`);

  assert.equal(segments.length, 2);
  assert.deepEqual(segments[0], { kind: "markdown", text: "사진을 다 봤어.\n" });
  assert.equal(segments[1].kind, "ask");
  const ask = segments[1].kind === "ask" ? segments[1].ask : null;
  assert.deepEqual(ask?.questions[0], {
    header: "식당 이름",
    text: "어느 식당에 다녀왔어?",
    multiple: false,
    options: [
      { label: "행복담", description: null },
      { label: "행복한 담벼락", description: "사진의 간판 글자" },
    ],
  });
  assert.equal(ask?.questions[1].multiple, true);
});

test("선택지 없는 질문은 직접 입력만 받는 질문이다", () => {
  const ask = parseAsk("<ask>\n<question>언제 갔어?</question>\n</ask>");

  assert.deepEqual(ask, { questions: [{ header: null, text: "언제 갔어?", multiple: false, options: [] }] });
});

test("코드 블록 안의 블록은 예시라 카드로 읽지 않는다", () => {
  const segments = splitAnswer("형식은 이렇다.\n\n```\n" + BLOCK + "\n```");

  assert.equal(segments.length, 1);
  assert.equal(segments[0].kind, "markdown");
});

test("모양이 어긋난 블록은 원문을 코드 블록으로 보인다", () => {
  const broken = "<ask>\n<option>질문 없는 선택지</option>\n</ask>";

  const segments = splitAnswer(`앞 글\n${broken}`);

  assert.equal(segments.length, 2);
  assert.equal(segments[1].kind, "markdown");
  assert.ok(segments[1].kind === "markdown" && segments[1].text.startsWith("```text\n<ask>"));
});

test("태그 안에 다른 태그가 오면 읽지 않는다", () => {
  assert.equal(parseAsk("<ask>\n<question>질문</question>\n<script>x</script>\n</ask>"), null);
});

test("한 질문에 같은 이름의 선택지가 둘이면 읽지 않는다", () => {
  assert.equal(parseAsk("<ask>\n<question>질문</question>\n<option>같다</option>\n<option>같다</option>\n</ask>"), null);
});

test("질문이 넷을 넘으면 읽지 않는다", () => {
  const five = Array.from({ length: 5 }, (_, index) => `<question>질문 ${index}</question>`).join("\n");

  assert.equal(parseAsk(`<ask>\n${five}\n</ask>`), null);
});

test("스트리밍 중에 닫히지 않은 블록은 그리지 않고, 끝난 답에서는 원문으로 보인다", () => {
  const partial = "사진을 봤어.\n<ask>\n<question>어디야?</question>";

  assert.deepEqual(splitAnswer(partial, true), [{ kind: "markdown", text: "사진을 봤어." }]);
  const finished = splitAnswer(partial);
  assert.equal(finished.length, 1);
  assert.ok(finished[0].kind === "markdown" && finished[0].text.includes("```text\n<ask>"));
});

test("이름표와 고른 답을 한 줄씩 적는다. 이름표가 없으면 질문을 쓴다", () => {
  const ask = parseAsk(BLOCK.replace(' header="먹은 메뉴"', ""))!;

  assert.equal(formatAnswers(ask, [["행복담"], ["국밥", "수육"]]), "식당 이름: 행복담\n무엇을 먹었어?: 국밥, 수육");
});

test("속성과 본문의 이스케이프를 푼다", () => {
  const ask = parseAsk('<ask>\n<question header="A &amp; B">&lt;둘&gt; 중 &quot;하나&quot;</question>\n</ask>');

  assert.equal(ask?.questions[0].header, "A & B");
  assert.equal(ask?.questions[0].text, '<둘> 중 "하나"');
});

test("스트리밍 중 반쯤 온 여는 태그는 그리지 않는다", () => {
  assert.deepEqual(splitAnswer("사진을 봤어.\n<as", true), [{ kind: "markdown", text: "사진을 봤어." }]);
  assert.deepEqual(splitAnswer("사진을 봤어.\n<as"), [{ kind: "markdown", text: "사진을 봤어.\n<as" }]);
});

test("네 칸 들여쓴 코드 블록과 물결 펜스 안의 블록은 카드로 읽지 않는다", () => {
  const indented = BLOCK.split("\n").map((line) => `    ${line}`).join("\n");

  assert.ok(splitAnswer(`예시\n\n${indented}`).every((segment) => segment.kind === "markdown"));
  assert.ok(splitAnswer(`예시\n~~~\n${BLOCK}\n~~~`).every((segment) => segment.kind === "markdown"));
});

test("원문 안에 펜스가 있어도 감싸는 코드 블록이 일찍 닫히지 않는다", () => {
  const broken = "<ask>\n```\n<question>질문</question>\n</ask>";

  const [segment] = splitAnswer(broken);

  assert.ok(segment.kind === "markdown" && segment.text.startsWith("````text\n") && segment.text.endsWith("\n````"));
});
