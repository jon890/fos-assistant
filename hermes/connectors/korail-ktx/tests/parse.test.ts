import { expect, test } from "bun:test";
import { classifyPage, pageError } from "../src/classify.ts";
import { checkSearch } from "../src/input.ts";
import { MissingFields, parseTrains } from "../src/parse-trains.ts";

const query = checkSearch(
  {
    departure: "서울",
    arrival: "부산",
    date: "2026-11-11",
    time_from: "07:30",
    adults: 2,
    children: 0,
    seniors: 0,
    seat_class: "general",
  },
  new Date("2026-11-10T00:00:00Z"),
);
const row = (over: Record<string, unknown> = {}) => ({
  type: "KTX",
  no: "열차번호 015",
  departs: "서울\n07:40",
  arrives: "부산\n10:20",
  general: { text: "예매 59,800원", bookable: true },
  first: { text: "매진", bookable: false },
  ...over,
});

test("줄을 결과로 바꾸고 이른 열차를 빼며 좌석 상태를 둘로 나눈다", () => {
  const trains = parseTrains(
    {
      empty: false,
      rows: [
        row({ departs: "07:10" }),
        row(),
        row({ no: "101", general: { text: "예약대기", bookable: false } }),
      ],
    },
    query,
  );
  expect(trains).toHaveLength(2);
  expect(trains[0]).toEqual({
    train_ref: "2026-11-11|07:40|KTX|015|서울|부산",
    train_type: "KTX",
    train_no: "015",
    departs_at: "2026-11-11T07:40",
    arrives_at: "2026-11-11T10:20",
    duration_min: 160,
    seat_status: "available",
    status_text: "예매 59,800원",
    adult_fare_krw: 59800,
  });
  expect(trains[1]).toMatchObject({
    seat_status: "sold_out",
    status_text: "예약대기",
    adult_fare_krw: null,
  });
});

test("자정을 넘는 열차는 다음 날 도착이다", () => {
  const [train] = parseTrains(
    { rows: [row({ departs: "23:30", arrives: "01:10" })] },
    query,
  );
  expect(train).toMatchObject({
    arrives_at: "2026-11-12T01:10",
    duration_min: 100,
  });
});

test("운행 열차가 없으면 빈 목록이고, 칸이 비면 지어내지 않는다", () => {
  expect(parseTrains({ empty: true, rows: [] }, query)).toEqual([]);
  expect(() => parseTrains(null, query)).toThrow(MissingFields);
  expect(() => parseTrains({ rows: [row({ general: null })] }, query)).toThrow(
    MissingFields,
  );
  expect(() => parseTrains({ rows: [row({ departs: "곧" })] }, query)).toThrow(
    MissingFields,
  );
});

test("화면 분류는 로그인, 탐지, 보안 문자, 거절, 정상 순서다", () => {
  const list = "https://www.korail.com/ticket/search/list";
  const bot =
    "안내 메시지 매크로, 개발자도구 등 미허가 도구 사용 시 이용이 제한될 수 있습니다. CODE : -8003";
  expect(
    classifyPage({
      href: "https://www.korail.com/ticket/login?x=1",
      notice: bot,
    }),
  ).toEqual({ kind: "login" });
  expect(classifyPage({ href: list, notice: bot, captcha: true })).toEqual({
    kind: "bot_check",
  });
  expect(classifyPage({ href: list, captcha: true, dialog: "x" })).toEqual({
    kind: "captcha",
  });
  expect(
    classifyPage({ href: list, dialog: "예매 가능 기간이 아닙니다" }),
  ).toEqual({ kind: "site_alert", message: "예매 가능 기간이 아닙니다" });
  expect(
    classifyPage({ href: list, notice: "안내 메시지 매크로 주의" }),
  ).toMatchObject({ kind: "site_alert" });
  expect(classifyPage({ href: list })).toEqual({ kind: "ok" });
  expect(
    pageError(classifyPage({ href: list, dialog: "가".repeat(300) }))?.message,
  ).toHaveLength(200);
  expect(pageError({ kind: "bot_check" })).toEqual({
    code: "KORAIL_BOT_CHECK",
    human: true,
  });
});
