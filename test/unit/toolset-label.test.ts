import assert from "node:assert/strict";
import test from "node:test";
import { toolsetText } from "../../web/src/lib/toolset-label.ts";

const NAMES = [
  "web", "vision", "todo", "clarify", "session_search", "skills", "tts", "delegation", "terminal", "file",
  "code_execution", "browser", "computer_use", "cronjob", "image_gen", "video_gen", "homeassistant", "spotify",
  "discord",
];
/** 표에 있으면 쓰이지 않아야 하는 값이다. 이 값이 돌아오면 그 이름이 표에 없다는 뜻이다. */
const FALLBACK = { label: "English Label", description: "설명한다" };

test("도구 묶음 열아홉이 모두 한국어 이름을 갖는다", () => {
  assert.equal(NAMES.length, 19);
  for (const name of NAMES) {
    const { label } = toolsetText(name, FALLBACK);
    assert.notEqual(label, FALLBACK.label, `${name} 이 표에 없다`);
    // Spotify 와 Discord 는 고유한 이름이라 그대로 둔다.
    if (name === "spotify" || name === "discord") continue;
    assert.match(label, /[가-힣]/, `${name} 의 이름 「${label}」 에 한글이 없다`);
    assert.doesNotMatch(label, /[A-Za-z]/, `${name} 의 이름 「${label}」 에 영어가 남았다`);
  }
});

test("표의 이름과 설명을 그대로 돌려준다", () => {
  assert.deepEqual(toolsetText("web", FALLBACK), { label: "웹 검색", description: "웹에서 찾아봐요" });
  assert.deepEqual(toolsetText("terminal", FALLBACK), { label: "명령 실행", description: "서버에서 명령을 실행해요" });
  assert.equal(toolsetText("spotify", FALLBACK).label, "Spotify");
  assert.equal(toolsetText("discord", FALLBACK).label, "Discord");
});

test("설명이 모두 「요」 로 끝난다", () => {
  for (const name of NAMES) {
    const { description } = toolsetText(name, FALLBACK);
    assert.ok(description.endsWith("요"), `${name} 의 설명 「${description}」 이 「요」 로 끝나지 않는다`);
  }
});

test("모르는 이름과 빈 이름과 Object 의 기본 속성 이름은 받은 값을 그대로 돌려준다", () => {
  assert.deepEqual(toolsetText("new_toolset", FALLBACK), FALLBACK);
  assert.deepEqual(toolsetText("", FALLBACK), FALLBACK);
  assert.deepEqual(toolsetText("constructor", FALLBACK), FALLBACK);
});
