import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { collectAnswers, type Question } from "./score.ts";

const file = JSON.parse(readFileSync(new URL("questions.json", import.meta.url), "utf8")) as { version: number; questions: Question[] };
const CATEGORIES = ["쇼핑·가격", "여행·장소", "뉴스·시세", "생활 지식"];

test("문항마다 id, 범주, 보낼 글, 보는 방식이 형식에 맞고 id 가 겹치지 않는다", () => {
  assert.equal(file.version, 1);
  const ids = new Set<string>();
  for (const question of file.questions) {
    assert.match(question.id, /^[a-z0-9]+(?:-[a-z0-9]+)*$/, question.id);
    assert.ok(!ids.has(question.id), `${question.id} 가 겹친다`);
    ids.add(question.id);
    assert.ok(CATEGORIES.includes(question.category), `${question.id} 의 범주`);
    assert.ok(question.messages.length > 0 && question.messages.every((text) => text.trim().length > 0), `${question.id} 의 글`);
    assert.ok(["sources", "date", "none", "photo-request"].includes(question.check), `${question.id} 의 check`);
    assert.equal(typeof question.compare, "boolean", `${question.id} 의 compare`);
  }
});

test("범주마다 문항이 있고 사람 비교 문항은 범주마다 고르게 있다", () => {
  for (const category of CATEGORIES) {
    const inCategory = file.questions.filter((question) => question.category === category);
    assert.ok(inCategory.length > 0, `${category} 문항`);
    assert.ok(inCategory.some((question) => question.compare), `${category} 의 사람 비교 문항`);
  }
});

test("날짜 문항은 하나이고 사람 비교 문항은 출처를 보는 문항이다", () => {
  assert.equal(file.questions.filter((question) => question.check === "date").length, 1);
  // 그림 참고 사진을 받지 못하는 사례다(스펙 tool-fallback REQ7). 사람 비교에 넣지 않는다
  const photo = file.questions.filter((question) => question.check === "photo-request");
  assert.equal(photo.length, 1);
  assert.equal(photo[0]!.compare, false);
  for (const question of file.questions.filter((item) => item.compare)) assert.equal(question.check, "sources", question.id);
});

const message = (role: string, executionId: number | null, content: string | null) => ({
  role,
  executionId,
  content,
  status: "COMPLETED",
  artifacts: [],
});

test("질문 뒤 자동 turn 의 답을 모으되 다음 사용자 질문과 그 답은 섞지 않는다", () => {
  const messages = [
    message("ASSISTANT", 99, "질문 이전 답"),
    message("USER", null, "첫 질문"),
    message("ASSISTANT", 1, "첫 답과 출처"),
    message("SYSTEM", null, "하위 작업이 끝났어요"),
    message("SYSTEM", null, "하위 작업이 끝났어요"),
    message("ASSISTANT", 3, "앞서 전한 내용의 후속 답"),
    message("ASSISTANT", 4, null),
    message("ASSISTANT", 5, "  "),
    message("USER", null, "후속 질문"),
    message("ASSISTANT", 2, "둘째 실행의 답"),
    message("ASSISTANT", 6, "둘째 실행 뒤 자동 turn 답"),
    message("USER", null, "평가 밖 질문"),
    message("ASSISTANT", 7, "평가 밖 답"),
  ];
  assert.deepEqual(collectAnswers(messages, [1]), ["첫 답과 출처", "앞서 전한 내용의 후속 답"]);
  assert.deepEqual(collectAnswers(messages, [1, null, 2, 1]), [
    "첫 답과 출처",
    "앞서 전한 내용의 후속 답",
    "둘째 실행의 답",
    "둘째 실행 뒤 자동 turn 답",
  ]);
  assert.deepEqual(collectAnswers(messages, [null]), []);
  assert.deepEqual(collectAnswers(messages, [99]), []);
});
