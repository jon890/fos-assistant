import assert from "node:assert/strict";
import test from "node:test";
import { choiceOf, describeSchedule, runReasonText, scheduleRequestOf } from "../../web/src/lib/task.ts";

const ZONE = "Asia/Seoul";

test("매일 09:30 은 30 9 * * * 이다", () => {
  assert.deepEqual(scheduleRequestOf({ kind: "daily", time: "09:30" }, ZONE), {
    type: "CRON",
    cron: "30 9 * * *",
    timeZone: ZONE,
  });
});

test("매주 월요일 09:00 은 0 9 * * 1 이다", () => {
  assert.equal(scheduleRequestOf({ kind: "weekly", weekday: 1, time: "09:00" }, ZONE).cron, "0 9 * * 1");
});

test("매달 1일 09:00 은 0 9 1 * * 이다", () => {
  assert.equal(scheduleRequestOf({ kind: "monthly", day: 1, time: "09:00" }, ZONE).cron, "0 9 1 * *");
});

test("한 번은 ONCE 와 시간대 없는 날짜와 시각이다", () => {
  assert.deepEqual(scheduleRequestOf({ kind: "once", date: "2026-11-01", time: "09:00" }, ZONE), {
    type: "ONCE",
    fireAt: "2026-11-01T09:00",
    timeZone: ZONE,
  });
});

test("직접 입력은 cron 을 그대로 보낸다", () => {
  assert.equal(scheduleRequestOf({ kind: "cron", cron: "*/15 * * * *" }, ZONE).cron, "*/15 * * * *");
});

test("choiceOf 는 위 변환의 반대로 돌아간다", () => {
  assert.deepEqual(choiceOf({ type: "CRON", cron: "30 9 * * *", fireAt: null }), { kind: "daily", time: "09:30" });
  assert.deepEqual(choiceOf({ type: "CRON", cron: "0 9 * * 1", fireAt: null }), {
    kind: "weekly",
    weekday: 1,
    time: "09:00",
  });
  assert.deepEqual(choiceOf({ type: "CRON", cron: "0 9 1 * *", fireAt: null }), {
    kind: "monthly",
    day: 1,
    time: "09:00",
  });
  assert.deepEqual(choiceOf({ type: "ONCE", cron: null, fireAt: "2026-11-01T09:00" }), {
    kind: "once",
    date: "2026-11-01",
    time: "09:00",
  });
});

test("넷 중 어느 모양도 아닌 cron 은 직접 입력이다", () => {
  assert.deepEqual(choiceOf({ type: "CRON", cron: "*/15 * * * *", fireAt: null }), {
    kind: "cron",
    cron: "*/15 * * * *",
  });
  assert.equal(choiceOf({ type: "CRON", cron: "0 9 1 6 *", fireAt: null }).kind, "cron");
});

test("시각을 사람 말로 적는다", () => {
  assert.equal(describeSchedule({ type: "CRON", cron: "0 9 * * *", fireAt: null }), "매일 09:00");
  assert.equal(describeSchedule({ type: "CRON", cron: "0 9 * * 1", fireAt: null }), "매주 월요일 09:00");
  assert.equal(describeSchedule({ type: "CRON", cron: "0 9 1 * *", fireAt: null }), "매달 1일 09:00");
  assert.equal(
    describeSchedule({ type: "ONCE", cron: null, fireAt: "2026-11-01T09:00" }),
    "한 번, 2026-11-01 09:00",
  );
  assert.equal(describeSchedule({ type: "CRON", cron: "0 9 1 * 1", fireAt: null }), "직접 입력: 0 9 1 * 1");
});

test("까닭을 화면 문구로 바꾸고 보이지 않을 까닭은 null 이다", () => {
  assert.equal(runReasonText("MISSED"), "서버가 꺼져 있던 동안의 실행이라 건너뛰었어요");
  assert.equal(runReasonText("PAUSED"), "작업을 멈춰 건너뛰었어요");
  assert.equal(runReasonText("DAILY_LIMIT"), "하루 실행 횟수를 다 썼어요");
  assert.equal(runReasonText("OWNER_REVOKED"), null);
  assert.equal(runReasonText(null), null);
});

test("초가 있는 ONCE 는 초를 보존해 되돌려 보낸다", () => {
  const choice = choiceOf({ type: "ONCE", cron: null, fireAt: "2026-11-01T09:00:30" });
  assert.deepEqual(choice, { kind: "once", date: "2026-11-01", time: "09:00:30" });
  assert.equal(scheduleRequestOf(choice, ZONE).fireAt, "2026-11-01T09:00:30");
});
