/** 목록 꺼내기 식이 준 화면 글을 결과로 바꾼다. 화면이 준 값은 믿지 않고 모양과 길이를 다시 본다. */
import type { Query } from "./input.ts";
import { list, text, won, type Json } from "./values.ts";

export class MissingFields extends Error {
  constructor(readonly fields: string[]) {
    super("missing");
  }
}

export type Train = {
  train_ref: string;
  train_type: string;
  train_no: string;
  departs_at: string;
  arrives_at: string;
  duration_min: number;
  seat_status: "available" | "sold_out";
  status_text: string;
  adult_fare_krw: number | null;
};

const CLOCK = /(\d{1,2}):(\d{2})/;

function minute(value: Json): number | null {
  const match = typeof value === "string" ? CLOCK.exec(value) : null;
  if (!match) return null;
  const hour = Number(match[1]);
  const min = Number(match[2]);
  return hour < 24 && min < 60 ? hour * 60 + min : null;
}

const clock = (m: number) =>
  `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;

function nextDay(date: string) {
  const day = new Date(`${date}T00:00:00Z`);
  day.setUTCDate(day.getUTCDate() + 1);
  return day.toISOString().slice(0, 10);
}

/** 칸 하나라도 비면 `MissingFields` 다. 이른 열차는 뺀다(spec DECISION18). */
export function parseTrains(state: Json, query: Query): Train[] {
  if (state === null || typeof state !== "object" || Array.isArray(state))
    throw new MissingFields(["list"]);
  const value = state as Record<string, Json>;
  if (value.empty === true) return [];
  const trains: Train[] = [];
  for (const row of list(value.rows)) {
    const r = (row ?? {}) as Record<string, Json>;
    const type = text(r.type, 30);
    const no = typeof r.no === "string" ? /\d{1,5}/.exec(r.no)?.[0] ?? null : null;
    const departs = minute(r.departs);
    const arrives = minute(r.arrives);
    const seat = r[query.seat_class] as { text?: Json; bookable?: Json } | null | undefined;
    const missing = [
      ...(type === null ? ["type"] : []),
      ...(no === null ? ["no"] : []),
      ...(departs === null ? ["departs"] : []),
      ...(arrives === null ? ["arrives"] : []),
      ...(seat === null || seat === undefined ? [`seat.${query.seat_class}`] : []),
    ];
    if (missing.length) throw new MissingFields(missing);
    if (departs! < query.fromMinute) continue;
    const overnight = arrives! < departs!;
    const seatText = text(seat!.text, 200) ?? "";
    const fare = /([\d,]+)\s*원/.exec(seatText)?.[1];
    trains.push({
      train_ref: [query.date, clock(departs!), type, no, query.departure, query.arrival].join("|"),
      train_type: type!,
      train_no: no!,
      departs_at: `${query.date}T${clock(departs!)}`,
      arrives_at: `${overnight ? nextDay(query.date) : query.date}T${clock(arrives!)}`,
      duration_min: (arrives! - departs! + (overnight ? 1440 : 0)),
      seat_status: seat!.bookable === true ? "available" : "sold_out",
      status_text: [...seatText].slice(0, 30).join(""),
      adult_fare_krw: fare === undefined ? null : won(fare),
    });
  }
  return trains;
}
