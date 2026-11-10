/**
 * 조작 단계 표. 단계마다 자리(fields.ts 의 상수에 확인한 값만 끼운 것), 동작, 넣을 글의 출처만 있다.
 * 모델이나 인자는 자리와 동작을 정하지 못한다. 예매·결제·「다음」·「더보기」·「다음날」 의 자리는 이 표에 없다.
 * 사람 속도 간격을 두지 않고 단계마다 한 번만 한다(spec DECISION9).
 */
import * as F from "./fields.ts";
import type { Query } from "./input.ts";

export type Step =
  | { name: string; kind: "click"; target: string; optional?: boolean }
  | { name: string; kind: "type"; text: string }
  | { name: string; kind: "select_all" };

const fill = (template: string, value: string) => template.replace("{}", value);

function station(name: string, input: string, value: string): Step[] {
  return [
    { name: `${name}.open`, kind: "click", target: input },
    { name: `${name}.clear`, kind: "select_all" },
    { name: `${name}.type`, kind: "type", text: value },
    {
      name: `${name}.pick`,
      kind: "click",
      target: fill(F.STATION_OPTION, value),
      optional: true,
    },
  ];
}

/** 어른은 처음에 1명이고 나머지는 0명이다. 그 차이만큼 더하기·빼기를 누른다. */
function passengers(query: Query): Step[] {
  const steps: Step[] = [
    { name: "passengers.open", kind: "click", target: F.PASSENGER_OPEN },
  ];
  for (const [field, kind] of Object.entries(F.PASSENGER_KINDS) as [
    keyof typeof F.PASSENGER_KINDS,
    string,
  ][]) {
    const diff = query[field] - (field === "adults" ? 1 : 0);
    const target = fill(diff > 0 ? F.PASSENGER_PLUS : F.PASSENGER_MINUS, kind);
    for (let i = 0; i < Math.abs(diff); i++)
      steps.push({ name: `passengers.${field}`, kind: "click", target });
  }
  steps.push({
    name: "passengers.confirm",
    kind: "click",
    target: F.PASSENGER_CONFIRM,
    optional: true,
  });
  return steps;
}

/** 조건 화면의 단계. 끝은 「열차 조회」 한 번이다. */
export function conditionSteps(query: Query): Step[] {
  return [
    {
      name: "notice.close",
      kind: "click",
      target: F.NOTICE_CLOSE,
      optional: true,
    },
    ...station("departure", F.DEPARTURE_INPUT, query.departure),
    ...station("arrival", F.ARRIVAL_INPUT, query.arrival),
    { name: "date.open", kind: "click", target: F.DATE_INPUT },
    {
      name: "date.day",
      kind: "click",
      target: fill(F.CALENDAR_DAY, query.date.replaceAll("-", "")),
    },
    {
      name: "date.hour",
      kind: "click",
      target: fill(F.CALENDAR_HOUR, String(query.hour).padStart(2, "0")),
    },
    {
      name: "date.confirm",
      kind: "click",
      target: F.CALENDAR_CONFIRM,
      optional: true,
    },
    ...passengers(query),
    { name: "lookup", kind: "click", target: F.LOOKUP_BUTTON },
  ];
}

/** 목록 화면의 단계. 수서 함께 보기는 아직 안 골랐을 때만 누른다(spec DECISION12). */
export function listSteps(query: Query, suseoChecked: boolean): Step[] {
  return [
    {
      name: "list.notice",
      kind: "click",
      target: F.NOTICE_CLOSE,
      optional: true,
    },
    ...(suseoChecked
      ? []
      : [
          {
            name: "list.suseo",
            kind: "click",
            target: F.SUSEO_TOGETHER,
          } as Step,
        ]),
    { name: "list.ktx", kind: "click", target: F.KTX_TAB },
    ...(query.seat_class === "first"
      ? [{ name: "list.first", kind: "click", target: F.FIRST_CLASS } as Step]
      : []),
  ];
}
