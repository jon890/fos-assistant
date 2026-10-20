import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import test from "node:test";

const DIR = new URL("../model-eval/blog/", import.meta.url);

type Photo = { id: string; file: string; scene: string; answer: string; accept: string[] };
type Message = { text: string; photos: string[] };
type Scenario = { id: string; messages: Message[] };

const photos: Photo[] = JSON.parse(readFileSync(new URL("photos/photos.json", DIR), "utf8")).photos;
const scenarios: Scenario[] = JSON.parse(readFileSync(new URL("scenarios.json", DIR), "utf8")).scenarios;

const squash = (text: string) => text.replace(/\s+/g, "");

/** 지시문에 허용 표기가 들어 있는 곳을 돌려준다. 들어 있으면 정답이 지시문으로 새는 것이다. */
function findLeaks(list: Scenario[], data: Photo[]): string[] {
  const leaks: string[] = [];
  for (const scenario of list) {
    for (const [index, message] of scenario.messages.entries()) {
      const text = squash(message.text);
      for (const photo of data) {
        for (const form of photo.accept) {
          if (text.includes(squash(form))) {
            leaks.push(`${scenario.id} 메시지 ${index + 1} 에 ${photo.id} 의 표기 「${form}」 이 있다`);
          }
        }
      }
    }
  }
  return leaks;
}

test("사진은 12장이고 id 와 file 이 겹치지 않으며 파일이 JPEG 다", () => {
  assert.equal(photos.length, 12);
  assert.equal(new Set(photos.map((p) => p.id)).size, 12, "id 가 겹친다");
  assert.equal(new Set(photos.map((p) => p.file)).size, 12, "file 이 겹친다");
  for (const photo of photos) {
    const url = new URL(`photos/${photo.file}`, DIR);
    assert.ok(existsSync(url), `${photo.file} 이 없다`);
    const head = readFileSync(url).subarray(0, 2);
    assert.deepEqual([...head], [0xff, 0xd8], `${photo.file} 이 JPEG 가 아니다`);
  }
});

test("accept 의 첫 값은 answer 다", () => {
  for (const photo of photos) {
    assert.equal(photo.accept[0], photo.answer, `${photo.id} 의 accept 첫 값이 answer 와 다르다`);
  }
});

test("다른 사진의 표기와 같거나 부분 문자열인 표기가 없다", () => {
  for (const a of photos) {
    for (const b of photos) {
      if (a.id === b.id) continue;
      for (const x of a.accept) {
        for (const y of b.accept) {
          const [sx, sy] = [squash(x), squash(y)];
          assert.ok(
            !sx.includes(sy) && !sy.includes(sx),
            `${a.id} 의 「${x}」 와 ${b.id} 의 「${y}」 가 겹쳐 거짓 통과를 낸다`,
          );
        }
      }
    }
  }
});

test("시나리오 id 가 넷이고 사진 id 가 모두 있으며 한 시나리오에서 사진이 겹치지 않는다", () => {
  assert.deepEqual(
    scenarios.map((s) => s.id).sort(),
    ["many-past", "mixed", "past-only", "same-message"],
  );
  const ids = new Set(photos.map((p) => p.id));
  for (const scenario of scenarios) {
    const used = scenario.messages.flatMap((m) => m.photos);
    for (const id of used) assert.ok(ids.has(id), `${scenario.id} 가 없는 사진 ${id} 를 쓴다`);
    assert.equal(new Set(used).size, used.length, `${scenario.id} 가 같은 사진을 두 번 쓴다`);
  }
});

test("마지막 메시지에만 {category} 가 한 번 있고 지난 사진 시나리오의 마지막 메시지에는 사진이 없다", () => {
  for (const scenario of scenarios) {
    const last = scenario.messages.length - 1;
    for (const [index, message] of scenario.messages.entries()) {
      const count = message.text.split("{category}").length - 1;
      assert.equal(count, index === last ? 1 : 0, `${scenario.id} 메시지 ${index + 1} 의 {category} 개수`);
    }
    if (scenario.id === "past-only" || scenario.id === "many-past") {
      assert.deepEqual(scenario.messages[last].photos, [], `${scenario.id} 의 마지막 메시지에 사진이 있다`);
    }
  }
});

test("어느 메시지에도 정답 표기가 들어 있지 않다", () => {
  assert.deepEqual(findLeaks(scenarios, photos), []);
});

test("지시문에 정답을 넣은 사본은 새는 것으로 거절한다", () => {
  const leaky = structuredClone(scenarios);
  leaky[0].messages[1].text += " 가상 국수";
  const leaks = findLeaks(leaky, photos);
  assert.equal(leaks.length, 1);
  assert.match(leaks[0], /past-only.*p01/);
});
