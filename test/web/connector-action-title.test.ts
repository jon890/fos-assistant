import assert from "node:assert/strict";
import test from "node:test";
import {
  actionDisplayTitle,
  UNNAMED_ACTION_TITLE,
} from "../../web/src/lib/connector-action-title.ts";

test("승인 카드의 도구 이름은 선언한 제목을 먼저 쓰고, 제목이 없으면 확인된 원래 도구 이름을 쓴다", () => {
  assert.equal(actionDisplayTitle("메모 쓰기", "write_note"), "메모 쓰기");
  // 표준 플러그인의 도구는 선언한 제목이 없어 Control Plane 이 고정 문구를 준다.
  assert.equal(
    actionDisplayTitle(UNNAMED_ACTION_TITLE, "list_notes"),
    "list_notes",
  );
  assert.equal(actionDisplayTitle(null, "list_notes"), "list_notes");
  assert.equal(actionDisplayTitle("  ", "list_notes"), "list_notes");
  // 원래 이름을 확인하지 못한 줄은 고정 문구다.
  assert.equal(
    actionDisplayTitle(UNNAMED_ACTION_TITLE, null),
    UNNAMED_ACTION_TITLE,
  );
  assert.equal(actionDisplayTitle(null, null), UNNAMED_ACTION_TITLE);
});
