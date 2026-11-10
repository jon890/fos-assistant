import { expect, test } from "bun:test";
import { checkSearch, todayKst, type SearchArgs } from "../src/input.ts";
import { stationName } from "../src/stations.ts";

const NOW = new Date("2026-11-10T03:00:00Z");
const BASE: SearchArgs = {
  departure: "서울역", arrival: "부산", date: "2026-11-11", time_from: "07:30",
  adults: 1, children: 1, seniors: 0, seat_class: "general",
};
const check = (over: Partial<SearchArgs>) => checkSearch({ ...BASE, ...over }, NOW);

test("끝의 「역」 만 떼고 표와 정확히 같은 이름만 받는다", () => {
  expect(stationName("서울역")).toBe("서울");
  expect(stationName(" 부산 ")).toBe("부산");
  expect(stationName("서울 역")).toBeNull();
  expect(stationName("서울특별시")).toBeNull();
  expect(stationName("수서")).toBe("수서");
});

test("시는 화면이 고르게 하고 분은 결과를 거를 기준으로 남긴다", () => {
  expect(check({})).toMatchObject({ departure: "서울", arrival: "부산", hour: 7, fromMinute: 450 });
});

test("모르는 역, 같은 역, 지난 날짜, 틀린 모양을 화면을 열기 전에 거절한다", () => {
  expect(() => check({ arrival: "부산진" })).toThrow(expect.objectContaining({ code: "KORAIL_UNKNOWN_STATION" }));
  expect(() => check({ arrival: "서울" })).toThrow(expect.objectContaining({ code: "KORAIL_INVALID_INPUT" }));
  expect(() => check({ date: "2026-11-09" })).toThrow(expect.objectContaining({ code: "KORAIL_DATE_UNAVAILABLE" }));
  for (const over of [{ date: "2026-02-30" }, { time_from: "24:00" }, { time_from: "7:30" }, { adults: 0, children: 0 }, { adults: 5, seniors: 5 }, { adults: 1.5 }, { seat_class: "vip" as never }])
    expect(() => check(over)).toThrow(expect.objectContaining({ code: "KORAIL_INVALID_INPUT" }));
});

test("오늘은 KST 날짜다", () => {
  expect(todayKst(new Date("2026-11-10T15:30:00Z"))).toBe("2026-11-11");
  expect(() => checkSearch({ ...BASE, date: "2026-11-10" }, new Date("2026-11-10T15:30:00Z"))).toThrow(
    expect.objectContaining({ code: "KORAIL_DATE_UNAVAILABLE" }),
  );
});
