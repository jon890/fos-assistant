import assert from "node:assert/strict";
import { test } from "node:test";
import {
  AnswerReveal,
  CATCH_UP_MS,
  FLUSH_MS,
} from "../../web/src/components/chat/answer-reveal.ts";

/** 가짜 시계와 화면 새로 고침이다. `advance` 가 16ms 마다 예약된 갱신을 돌린다. */
function harness(reducedMotion = false) {
  let time = 1000;
  let pending: (() => void) | null = null;
  const reveal = new AnswerReveal({
    now: () => time,
    schedule: (callback) => {
      pending = callback;
      return () => {
        if (pending === callback) pending = null;
      };
    },
    reducedMotion: () => reducedMotion,
  });
  const seen: string[] = [];
  reveal.subscribe(() => seen.push(reveal.getSnapshot().text));
  return {
    reveal,
    seen,
    text: () => reveal.getSnapshot().text,
    advance(ms: number, step = 16) {
      for (let left = ms; left > 0; left -= step) {
        time += Math.min(step, left);
        const run = pending;
        pending = null;
        run?.();
      }
    },
    /** 화면이 멈췄다가 한 번에 돌아온 것처럼 시간만 흘리고 갱신을 한 번 돌린다. */
    jump(ms: number) {
      time += ms;
      const run = pending;
      pending = null;
      run?.();
    },
    scheduled: () => pending !== null,
  };
}

const SENTENCE = "오늘은 날씨가 맑고 바람이 시원해서 산책하기 좋은 날이에요. ";

test("낱말 중간에서 끊지 않고 낱말 단위로 보인다", () => {
  const h = harness();
  h.reveal.receive(SENTENCE.repeat(3));
  h.advance(600);
  assert.ok(h.seen.length > 3);
  for (const shown of h.seen) {
    if (shown === "") continue;
    assert.match(shown, /\s$/, `낱말 중간에서 끊겼다: ${shown}`);
    assert.ok(SENTENCE.repeat(3).startsWith(shown));
  }
});

test("받은 글이 적으면 초당 55자 안팎으로 이어 보인다", () => {
  const h = harness();
  h.reveal.receive("가나다 라마바 사아자 ");
  h.advance(48);
  assert.equal(h.text(), "가나다 ");
  h.advance(160);
  assert.equal(h.text(), "가나다 라마바 사아자 ");
});

test("밀린 글은 받은 뒤 0.25초 안에 모두 보인다", () => {
  const h = harness();
  h.reveal.receive(SENTENCE.repeat(20));
  h.advance(CATCH_UP_MS / 2);
  assert.ok(h.text().length > 0 && h.text().length < SENTENCE.length * 20);
  h.advance(CATCH_UP_MS / 2 + 16);
  assert.equal(h.text(), SENTENCE.repeat(20));
});

test("탭이 뒤에 있다 돌아오면 갱신 한 번으로 밀린 글을 따라잡는다", () => {
  const h = harness();
  h.reveal.receive(SENTENCE.repeat(5));
  h.advance(32);
  h.reveal.receive(SENTENCE.repeat(5));
  h.jump(5000);
  assert.equal(h.text(), SENTENCE.repeat(10));
  h.advance(300);
  assert.equal(h.scheduled(), false);
});

test("끝 사건 뒤에는 남은 글이 0.15초 안에 모두 보이고 이행한다", async () => {
  const h = harness();
  h.reveal.receive(SENTENCE.repeat(4));
  h.advance(32);
  let finished = false;
  const done = h.reveal.finish().then(() => {
    finished = true;
  });
  h.advance(FLUSH_MS);
  await done;
  assert.ok(finished);
  assert.equal(h.text(), SENTENCE.repeat(4));
});

test("끝 사건에는 낱말 끝이 아닌 마지막 글과 닫히지 않은 기호도 모두 보인다", async () => {
  const h = harness();
  h.reveal.receive("앞 글 **닫히지 않은 마지막");
  h.advance(100);
  assert.equal(h.text(), "앞 글 ");
  const done = h.reveal.finish();
  h.advance(FLUSH_MS);
  await done;
  assert.equal(h.text(), "앞 글 **닫히지 않은 마지막");
});

test("다 보인 글에는 끝 사건이 바로 이행한다", async () => {
  const h = harness();
  await h.reveal.finish();
  h.reveal.receive("가나 ");
  h.advance(300);
  await h.reveal.finish();
});

test("공백이 오지 않은 마지막 낱말은 글이 잠시 멈추면 보인다", () => {
  const h = harness();
  h.reveal.receive("안녕하세");
  h.advance(200);
  assert.equal(h.text(), "");
  h.advance(CATCH_UP_MS);
  assert.equal(h.text(), "안녕하세");
});

test("닫히지 않은 굵은 글씨는 닫는 기호가 올 때까지 앞에서 멈췄다가 한 번에 보인다", () => {
  const h = harness();
  h.reveal.receive("문장 **굵게 말");
  h.advance(1000);
  assert.equal(h.text(), "문장 ");
  h.reveal.receive("하는 중** 끝 ");
  h.advance(300);
  assert.equal(h.text(), "문장 **굵게 말하는 중** 끝 ");
  for (const shown of h.seen)
    assert.ok(
      (shown.match(/\*\*/g) ?? []).length % 2 === 0,
      `열린 기호가 보였다: ${shown}`,
    );
});

test("닫히지 않은 인라인 코드와 링크 괄호도 닫힐 때까지 멈춘다", () => {
  const code = harness();
  code.reveal.receive("값은 `a b");
  code.advance(1000);
  assert.equal(code.text(), "값은 ");
  code.reveal.receive(" c` 입니다 ");
  code.advance(300);
  assert.equal(code.text(), "값은 `a b c` 입니다 ");

  const link = harness();
  link.reveal.receive("참고 [문서](https://exa");
  link.advance(1000);
  assert.equal(link.text(), "참고 ");
  link.reveal.receive("mple.com) 를 ");
  link.advance(300);
  assert.equal(link.text(), "참고 [문서](https://example.com) 를 ");
});

test("문단이 끝날 때까지 닫히지 않은 기호는 글자 그대로 보인다", () => {
  const h = harness();
  h.reveal.receive("2 ** 3 은 8 이에요.\n\n다음 문단 ");
  h.advance(600);
  assert.equal(h.text(), "2 ** 3 은 8 이에요.\n\n다음 문단 ");
});

test("코드 블록은 줄이 끝난 뒤에 줄 단위로 보인다", () => {
  const h = harness();
  h.reveal.receive("설명 입니다\n```js\nconst a = 1;\nconst bb");
  h.advance(1000);
  assert.equal(h.text(), "설명 입니다\n```js\nconst a = 1;\n");
  h.reveal.receive(" = 2;\n```\n끝 ");
  h.advance(300);
  assert.equal(h.text(), "설명 입니다\n```js\nconst a = 1;\nconst bb = 2;\n```\n끝 ");
});

test("reset 은 받은 글과 보인 글을 모두 비운다", () => {
  const h = harness();
  h.reveal.receive(SENTENCE);
  h.advance(300);
  assert.notEqual(h.text(), "");
  h.reveal.reset();
  assert.equal(h.text(), "");
  h.reveal.receive("새 글 ");
  h.advance(300);
  assert.equal(h.text(), "새 글 ");
});

test("받은 글이 줄면 보인 글이 받은 글보다 길어지지 않게 바로 맞춘다", () => {
  const h = harness();
  h.reveal.receive(SENTENCE);
  h.advance(300);
  assert.equal(h.text(), SENTENCE);
  h.reveal.replace("오늘은 날씨가 ");
  assert.equal(h.text(), "오늘은 날씨가 ");
  h.reveal.receive("다시 ");
  h.advance(300);
  assert.equal(h.text(), "오늘은 날씨가 다시 ");
});

test("새로 보인 낱말은 흐릿했다가 또렷해지고 시간이 지나면 사라진다", () => {
  const h = harness();
  h.reveal.receive("가나다 ");
  h.advance(48);
  const first = h.reveal.getSnapshot().fades;
  assert.equal(first.length, 1);
  assert.deepEqual([first[0].from, first[0].to], [0, 4]);
  assert.ok(first[0].progress < 1);
  h.advance(48);
  assert.ok(h.reveal.getSnapshot().fades[0].progress > first[0].progress);
  h.advance(400);
  assert.deepEqual(h.reveal.getSnapshot().fades, []);
  assert.equal(h.scheduled(), false);
});

test("닫히지 않은 기호 앞에서 멈춰도 먼저 보인 낱말의 떠오름은 끝난다", () => {
  const h = harness();
  h.reveal.receive("문장 **굵게 말");
  h.advance(48);
  assert.equal(h.text(), "문장 ");
  assert.equal(h.reveal.getSnapshot().fades.length, 1);
  h.advance(400);
  assert.deepEqual(h.reveal.getSnapshot().fades, []);
  assert.equal(h.text(), "문장 ");
  assert.equal(h.scheduled(), false);
});

test("동작 줄이기가 켜져 있으면 떠오름 없이 낱말 단위로만 보인다", () => {
  const h = harness(true);
  h.reveal.receive(SENTENCE);
  h.advance(48);
  assert.notEqual(h.text(), "");
  for (let i = 0; i < 20; i++) {
    h.advance(16);
    assert.deepEqual(h.reveal.getSnapshot().fades, []);
  }
});

test("글이 모두 보이고 떠오름이 끝나면 더는 갱신을 예약하지 않는다", () => {
  const h = harness();
  h.reveal.receive("가나다 ");
  h.advance(1000);
  assert.equal(h.scheduled(), false);
});
