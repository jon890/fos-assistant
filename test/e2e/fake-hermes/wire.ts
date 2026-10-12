import { readFileSync } from "node:fs";
import type { ServerResponse } from "node:http";
import type { Run } from "./state.ts";

const contract = readFileSync(new URL("../../../hermes/tests/hermes_contract.py", import.meta.url), "utf8");
const version = /^HERMES_VERSION = "(v[0-9.]+)"$/m.exec(contract)?.[1];
if (version === undefined) throw new Error("Hermes 계약의 판 번호를 읽을 수 없다");
export const HERMES_VERSION = version;

/** TS와 Java가 같은 판의 합성 wire를 읽는다. 없는 판으로 바꾸면 바로 실패한다. */
export function wireFixture(name: string): string {
  return readFileSync(new URL(`../../../backend/src/test/resources/hermes/${HERMES_VERSION}/${name}.sse`, import.meta.url), "utf8");
}

export function wireEvents(wire: string): Record<string, unknown>[] {
  return wire.split("\n").filter((line) => line.startsWith("data:"))
    .map((line) => JSON.parse(line.slice(5).trimStart()) as Record<string, unknown>);
}

const templates = new Map(wireEvents(wireFixture("events")).map((payload) => [payload.event, payload]));

/** 판별 칸은 공유 표본에서 가져오고 실행 식별자와 종료 결과는 실제 대역 상태에서 붙인다. */
export function eventEnvelope(run: Pick<Run, "run_id" | "output" | "usage">, fields: Record<string, unknown>): Record<string, unknown> {
  const template = templates.get(fields.event);
  if (template === undefined) throw new Error(`${HERMES_VERSION}의 사건 표본이 없다: ${fields.event}`);
  const { run_id: _runId, timestamp: _timestamp, ...defaults } = template;
  const payload: Record<string, unknown> = { ...defaults, ...fields, run_id: run.run_id, timestamp: fields.timestamp ?? Date.now() / 1000 };
  if (fields.event === "run.completed") {
    payload.output = fields.output ?? run.output;
    payload.usage = fields.usage ?? run.usage;
  }
  return payload;
}

export function runEventWriter(response: ServerResponse, run: Pick<Run, "run_id" | "output" | "usage">) {
  return (fields: Record<string, unknown>): void => {
    response.write(`data: ${JSON.stringify(eventEnvelope(run, fields))}\n\n`);
  };
}
