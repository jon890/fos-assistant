/** `search_trains` 의 인자를 탭을 열기 전에 확인한다. 잘못된 인자로 코레일 화면을 움직이지 않는다. */
import { ToolError } from "./errors.ts";
import { stationName } from "./stations.ts";

export type SeatClass = "general" | "first";

export type SearchArgs = {
  departure: string;
  arrival: string;
  date: string;
  time_from: string;
  adults: number;
  children: number;
  seniors: number;
  seat_class: SeatClass;
};

export type Query = {
  departure: string;
  arrival: string;
  /** `YYYY-MM-DD`, KST. */
  date: string;
  /** 코레일 화면이 고르게 하는 시(0~23). */
  hour: number;
  /** 이 분보다 이른 열차는 결과에서 뺀다. 하루의 분(0~1439). */
  fromMinute: number;
  adults: number;
  children: number;
  seniors: number;
  seat_class: SeatClass;
};

export const PASSENGERS_MAX = 9;
const DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const TIME = /^([01]\d|2[0-3]):([0-5]\d)$/;

const invalid = () => new ToolError("KORAIL_INVALID_INPUT");

/** 오늘(KST)의 `YYYY-MM-DD`. */
export function todayKst(now = new Date()) {
  return new Date(now.getTime() + 9 * 3_600_000).toISOString().slice(0, 10);
}

function count(value: number) {
  if (!Number.isInteger(value) || value < 0 || value > PASSENGERS_MAX)
    throw invalid();
  return value;
}

export function checkSearch(args: SearchArgs, now = new Date()): Query {
  const departure = stationName(args.departure);
  const arrival = stationName(args.arrival);
  if (departure === null || arrival === null)
    throw new ToolError("KORAIL_UNKNOWN_STATION");
  if (departure === arrival) throw invalid();
  const date = DATE.exec(args.date);
  if (!date) throw invalid();
  const parsed = new Date(`${args.date}T00:00:00Z`);
  if (
    Number.isNaN(parsed.getTime()) ||
    parsed.toISOString().slice(0, 10) !== args.date
  )
    throw invalid();
  if (args.date < todayKst(now)) throw new ToolError("KORAIL_DATE_UNAVAILABLE");
  const time = TIME.exec(args.time_from);
  if (!time) throw invalid();
  const adults = count(args.adults);
  const children = count(args.children);
  const seniors = count(args.seniors);
  const total = adults + children + seniors;
  if (total < 1 || total > PASSENGERS_MAX) throw invalid();
  if (args.seat_class !== "general" && args.seat_class !== "first")
    throw invalid();
  const hour = Number(time[1]);
  return {
    departure,
    arrival,
    date: args.date,
    hour,
    fromMinute: hour * 60 + Number(time[2]),
    adults,
    children,
    seniors,
    seat_class: args.seat_class,
  };
}
