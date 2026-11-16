import assert from "node:assert/strict";
import test from "node:test";
import type { ChatEvent, SteerInfo } from "../../src/lib/chat-event.ts";
import type { Turn } from "../../src/components/chat/message-types.ts";
import {
  STEER_CHILD_NOTICE,
  STEER_STATE_TEXT,
  applySteerEvent,
} from "../../src/components/chat/steer.ts";
import { visibleTurns } from "../../src/components/chat/visible-turns.ts";

const DELIVERED: SteerInfo = { state: "DELIVERED", child: null };

function turn(
  id: Turn["id"],
  role: Turn["role"],
  extra: Partial<Turn> = {},
): Turn {
  return { id, role, content: `${role}-${id}`, senderName: null, ...extra };
}

function steerEvent(
  messageId: number,
  steer: SteerInfo,
  executionId: number | null = 7,
): ChatEvent {
  return { type: "steer", messageId, text: "창가로", executionId, steer };
}

const ids = (turns: Turn[]) => turns.map((line) => line.id);

test("상태 글과 안내 줄은 스펙의 글 그대로다", () => {
  assert.deepEqual(STEER_STATE_TEXT, {
    DELIVERED: "전달됨",
    CARRIED_OVER: "다음 차례로 넘어감",
    UNCONFIRMED: "전달됐는지 확인 못 함",
  });
  assert.equal(
    STEER_CHILD_NOTICE.SUBAGENT,
    "도우미가 하던 일은 바꾸지 못하고, 도우미가 끝난 뒤 반영돼요",
  );
  assert.equal(
    STEER_CHILD_NOTICE.DELEGATION,
    "맡긴 도우미가 하던 일은 바꾸지 못해요",
  );
});

test("흐르는 답이 있으면 그 바로 위에 말풍선을 넣는다", () => {
  const turns = [turn("pending-1", "USER"), turn("assistant-1", "ASSISTANT")];
  const next = applySteerEvent(turns, steerEvent(40, DELIVERED));
  assert.deepEqual(ids(next), ["pending-1", "steer-40", "assistant-1"]);
  assert.equal(next[1].content, "창가로");
  assert.deepEqual(next[1].steer, DELIVERED);
});

test("답이 아직 없으면 끝에 넣어 뒤에 생길 답이 아래에 붙는다", () => {
  const next = applySteerEvent(
    [turn("pending-1", "USER")],
    steerEvent(40, DELIVERED),
  );
  assert.deepEqual(ids(next), ["pending-1", "steer-40"]);
});

test("같은 turn 에 여러 번 보내면 받은 순서대로 답 위에 쌓인다", () => {
  let turns = [turn(1, "USER"), turn("assistant-auto-1", "ASSISTANT")];
  turns = applySteerEvent(turns, steerEvent(40, DELIVERED));
  turns = applySteerEvent(turns, steerEvent(41, DELIVERED));
  assert.deepEqual(ids(turns), [1, "steer-40", "steer-41", "assistant-auto-1"]);
});

test("같은 번호의 사건은 상태만 바꾼다. 이력을 다시 읽은 저장된 줄도 같다", () => {
  const carried: SteerInfo = { state: "CARRIED_OVER", child: null };
  const live = applySteerEvent(
    [turn("steer-40", "USER", { steer: DELIVERED })],
    steerEvent(40, carried),
  );
  assert.deepEqual(live[0].steer, carried);
  const saved = applySteerEvent(
    [turn(40, "USER", { steer: DELIVERED }), turn(41, "ASSISTANT")],
    steerEvent(40, carried),
  );
  assert.deepEqual(ids(saved), [40, 41]);
  assert.deepEqual(saved[0].steer, carried);
});

test("그 실행의 답이 이미 저장됐으면 저장된 줄로 넣어 이력과 같이 접힌다", () => {
  const turns = [turn(1, "USER"), turn(42, "ASSISTANT", { executionId: 7 })];
  const next = applySteerEvent(turns, steerEvent(40, DELIVERED, 7));
  assert.deepEqual(ids(next), [1, 40, 42]);
});

test("steer 사건이 아니거나 칸이 비면 그대로 둔다", () => {
  const turns = [turn(1, "USER")];
  assert.equal(applySteerEvent(turns, { type: "pending" }), turns);
  assert.equal(
    applySteerEvent(turns, { type: "steer", messageId: 3, steer: null }),
    turns,
  );
});

test("이력의 steer 말풍선은 질문과 답 사이에 놓이고 그 답은 다시 생성할 수 없다", () => {
  const { visible } = visibleTurns(
    [
      turn(1, "USER"),
      turn(2, "USER", { steer: DELIVERED }),
      turn(3, "ASSISTANT"),
      turn(4, "USER"),
      turn(5, "ASSISTANT"),
    ],
    {},
  );
  assert.deepEqual(
    visible.map(({ turn: line, afterSteer }) => [line.id, afterSteer === true]),
    [
      [1, false],
      [2, false],
      [3, true],
      [4, false],
      [5, false],
    ],
  );
});

test("돌아온 글로 연 turn 의 답은 앞 답 뒤에 이어지고 말풍선은 앞 답 위에 남는다", () => {
  const carried: SteerInfo = { state: "CARRIED_OVER", child: null };
  const { visible } = visibleTurns(
    [
      turn(1, "USER"),
      turn(2, "USER", { steer: carried }),
      turn(3, "ASSISTANT"),
      turn(5, "USER", { steer: DELIVERED }),
      turn(6, "ASSISTANT"),
    ],
    {},
  );
  assert.deepEqual(
    visible.map(({ turn: line }) => line.id),
    [1, 2, 3, 5, 6],
  );
  assert.equal(visible.at(-1)?.afterSteer, true);
});

test("도는 동안의 말풍선 뒤 흐르는 답도 다시 생성 단추를 두지 않는다", () => {
  const { visible } = visibleTurns(
    [
      turn(1, "USER"),
      turn(2, "ASSISTANT"),
      turn("pending-1", "USER"),
      turn("steer-9", "USER", { steer: DELIVERED }),
      turn("assistant-1", "ASSISTANT"),
    ],
    {},
  );
  assert.equal(visible.at(-1)?.afterSteer, true);
  assert.equal(visible[1].afterSteer, false);
});
