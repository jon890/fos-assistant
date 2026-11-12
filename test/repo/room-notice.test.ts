import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";
import { ROOM_SUMMARY_TITLE, roomSummaryOf } from "../../web/src/lib/room.ts";

const ROOM_CONTEXT = join(
  import.meta.dirname,
  "../../backend/src/main/java/com/bifos/assistant/chat/turn/application/RoomContext.java",
);

test("대화방 요약 줄의 첫 줄은 backend 의 RoomContext.NOTICE_TITLE 과 같다", async () => {
  const source = await readFile(ROOM_CONTEXT, "utf8");
  const match = /NOTICE_TITLE = "([^"]+)";/.exec(source);
  assert.ok(match, "RoomContext 에서 NOTICE_TITLE 을 찾지 못했다");
  assert.equal(match[1], ROOM_SUMMARY_TITLE);
});

test("요약 줄이면 첫 줄과 빈 줄 뒤의 요약을 돌려주고 다른 알림 줄은 null 이다", () => {
  assert.equal(roomSummaryOf(`${ROOM_SUMMARY_TITLE}\n\n등산 준비를 이야기했다`), "등산 준비를 이야기했다");
  assert.equal(roomSummaryOf("맡긴 일의 결과를 다시 전해요"), null);
  assert.equal(roomSummaryOf(ROOM_SUMMARY_TITLE), null);
});
