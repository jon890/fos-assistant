// 서비스 개념 평가의 판정 규칙이다. 네트워크와 파일을 쓰지 않는 순수 함수만 둔다.
// 문항 형식과 세는 규칙은 docs/features/service-concepts/design.md 의 「평가 세트」 가, 기준은 같은 문서의 DECISION4 가 갖는다.
import type { ExecutionNode } from "../control-plane.ts";

export const CONCEPTS = ["file_space", "schedule", "memory", "follow_up", "connection", "agent_pick"] as const;
export type Concept = (typeof CONCEPTS)[number];
/** 문항이 쓸 수 있는 자리다. 운영에 따라 달라지는 값이라 실행할 때 환경 변수로 채운다. */
export const PLACEHOLDERS = ["otherAgentName", "otherAgentCode", "connectorTitle", "connectorId"] as const;
export type Placeholder = (typeof PLACEHOLDERS)[number];
export type Values = Partial<Record<Placeholder, string>>;
export type Expect = {
  /** 경로와 질의 문자열이 똑같은 링크가 있어야 한다. */
  links?: string[];
  /** 이 앞부분으로 시작하는 링크가 있어야 한다. */
  linkPrefixes?: string[];
  /** 이 앞부분으로 시작하는 링크가 없어야 한다. */
  noLinkPrefixes?: string[];
  /** `mcp__fos_assistant__<도구>` 로 끝나는 도구가 끝난 사건이 있어야 한다. */
  tools?: string[];
  /** 끝난 사건에 없어야 할 Control Plane 도구 이름이다. */
  noTools?: string[];
  /** 낱말 가운데 하나 이상이 답에 있어야 한다. */
  includes?: string[];
  /** 낱말이 답에 하나도 없어야 한다. */
  absent?: string[];
  /**
   * 답이 링크한 예약 작업(`/tasks/<id>`)의 지시문에 있어야 할 말이다. 항목마다 `|` 로 나눈 낱말 가운데 하나가 있으면 된다.
   * 스펙 service-concepts DECISION28 의 지시문 요소를 본다.
   */
  instructionIncludes?: string[];
};
export type Question = { id: string; concept: Concept; messages: string[]; expect: Expect };
export type QuestionScore = { id: string; concept: Concept; passed: boolean; skipped: boolean; failures: string[]; error?: string };
export type SetOutcome = "PASS" | "FAIL" | "ERROR";
export type SetScore = { outcome: SetOutcome; passed: number; ran: number; needed: number; skipped: number; errors: number };

/** 돌린 문항 가운데 통과해야 하는 비율이다. 답변 품질 평가의 출처 기준(15/17)과 같은 크기다. */
export const PASS_RATIO = 0.85;
const TOOL_PREFIX = "mcp__fos_assistant__";
const SLOT = /\{([A-Za-z]+)\}/g;
const MARKDOWN_TARGET = /\]\(\s*([^)\s]+)\s*\)/g;
/** 앞이 글자나 슬래시가 아닌 곳에서 시작하는 서비스 안 주소다. */
const BARE_PATH = /(?<![\w/.:-])(\/[A-Za-z][^\s)\]」』"'`<>,]*)/g;
const ABSOLUTE = /^https?:\/\/[^/\s]+(\/[^\s]*)?$/;

function fillText(text: string, values: Values): string | null {
  let missing = false;
  const filled = text.replace(SLOT, (whole, name: string) => {
    const value = (PLACEHOLDERS as readonly string[]).includes(name) ? values[name as Placeholder] : undefined;
    if (!value) missing = true;
    return value ?? whole;
  });
  return missing ? null : filled;
}

/** 문항의 자리를 채운다. 쓴 자리의 값이 하나라도 비면 null 이다. 그 문항은 건너뛴다. */
export function fill(question: Question, values: Values): Question | null {
  const texts = (list?: string[]) => list?.map((text) => fillText(text, values));
  const messages = texts(question.messages)!;
  const expect: Expect = {};
  for (const key of Object.keys(question.expect) as (keyof Expect)[]) expect[key] = texts(question.expect[key]) as string[];
  const all = [...messages, ...Object.values(expect).flat()];
  if (all.some((text) => text === null)) return null;
  return { ...question, messages: messages as string[], expect };
}

/** 쓴 자리 이름이다. 문항 형식 검사가 쓴다. */
export function placeholdersOf(question: Question): string[] {
  const texts = [...question.messages, ...Object.values(question.expect).flat()];
  return [...new Set(texts.flatMap((text) => [...text.matchAll(SLOT)].map((match) => match[1])))];
}

function normalize(target: string): string | null {
  const absolute = ABSOLUTE.exec(target);
  if (absolute) return absolute[1] ?? "/";
  return target.startsWith("/") ? target : null;
}

/** 답에 든 서비스 안 주소다. 마크다운 링크의 대상과 글 속의 맨 주소를 모두 본다. 전체 주소는 경로와 질의 문자열만 남긴다. */
export function answerLinks(answer: string): string[] {
  const found = new Set<string>();
  for (const match of answer.matchAll(MARKDOWN_TARGET)) {
    const path = normalize(match[1]);
    if (path) found.add(path);
  }
  for (const match of answer.replace(MARKDOWN_TARGET, "]").matchAll(BARE_PATH)) found.add(match[1].replace(/[.。]+$/, ""));
  return [...found];
}

const TASK_LINK = /^\/tasks\/([0-9a-fA-F-]{36})$/;

/** 답이 링크한 예약 작업의 번호다. 에이전트가 만든 작업을 찾는 데 쓴다. */
export function linkedTaskIds(answer: string): string[] {
  return answerLinks(answer).flatMap((link) => {
    const match = TASK_LINK.exec(link);
    return match ? [match[1]] : [];
  });
}

/** 실행 트리 전체에서 끝난 Control Plane 도구의 이름이다. */
export function completedTools(root: ExecutionNode): Set<string> {
  const names = new Set<string>();
  const visit = (node: ExecutionNode) => {
    for (const event of node.events)
      if (event.eventType === "TOOL_COMPLETED" && event.toolName?.startsWith(TOOL_PREFIX))
        names.add(event.toolName.slice(TOOL_PREFIX.length));
    node.children.forEach(visit);
  };
  visit(root);
  return names;
}

/** @param instructions 답이 링크한 예약 작업들의 지시문. `instructionIncludes` 만 본다 */
export function scoreQuestion(question: Question, answer: string, root: ExecutionNode, instructions: string[] = []): QuestionScore {
  const links = answerLinks(answer);
  const tools = completedTools(root);
  const { expect } = question;
  const failures: string[] = [];
  for (const link of expect.links ?? []) if (!links.includes(link)) failures.push(`링크 ${link} 가 없다`);
  for (const prefix of expect.linkPrefixes ?? [])
    if (!links.some((link) => link.startsWith(prefix))) failures.push(`${prefix} 로 시작하는 링크가 없다`);
  for (const prefix of expect.noLinkPrefixes ?? [])
    if (links.some((link) => link.startsWith(prefix))) failures.push(`${prefix} 로 시작하는 링크가 있다`);
  for (const tool of expect.tools ?? []) if (!tools.has(tool)) failures.push(`도구 ${tool} 를 부르지 않았다`);
  for (const tool of expect.noTools ?? []) if (tools.has(tool)) failures.push(`없어야 할 도구 ${tool} 를 불렀다`);
  if (expect.includes && !expect.includes.some((word) => answer.includes(word)))
    failures.push(`${expect.includes.join(", ")} 가운데 하나도 없다`);
  for (const word of expect.absent ?? []) if (answer.includes(word)) failures.push(`없어야 할 「${word}」 가 있다`);
  if (expect.instructionIncludes && instructions.length === 0) failures.push("답이 링크한 예약 작업의 지시문이 없다");
  else
    for (const item of expect.instructionIncludes ?? []) {
      const words = item.split("|");
      if (!instructions.some((text) => words.some((word) => text.includes(word))))
        failures.push(`지시문에 ${words.join(", ")} 가운데 하나도 없다`);
    }
  return { id: question.id, concept: question.concept, passed: failures.length === 0, skipped: false, failures };
}

export function skippedQuestion(question: Question): QuestionScore {
  return { id: question.id, concept: question.concept, passed: false, skipped: true, failures: [] };
}

export function failedQuestion(question: Question, error: string): QuestionScore {
  return { id: question.id, concept: question.concept, passed: false, skipped: false, failures: [], error };
}

/** 건너뛴 문항은 분모에서 뺀다. 보내기나 읽기가 실패한 문항이 하나라도 있으면 ERROR 다. */
export function scoreSet(scores: QuestionScore[]): SetScore {
  const ran = scores.filter((score) => !score.skipped);
  const passed = ran.filter((score) => score.passed).length;
  const errors = ran.filter((score) => score.error).length;
  const needed = Math.ceil(ran.length * PASS_RATIO);
  const outcome: SetOutcome = errors > 0 ? "ERROR" : ran.length > 0 && passed >= needed ? "PASS" : "FAIL";
  return { outcome, passed, ran: ran.length, needed, skipped: scores.length - ran.length, errors };
}

export function exitCode(outcome: SetOutcome): number {
  return outcome === "PASS" ? 0 : outcome === "FAIL" ? 1 : 2;
}

export function markdown(label: string, tier: string, scores: QuestionScore[], set: SetScore): string {
  const rows = scores.map((score) => {
    const result = score.skipped ? "건너뜀" : score.error ? `오류: ${score.error}` : score.passed ? "통과" : "미달";
    return `| ${score.id} | ${score.concept} | ${result} | ${score.failures.join("; ")} |`;
  });
  return [
    `# 서비스 개념 평가${label ? ` ${label}` : ""}`,
    "",
    `모델 칸: ${tier || "기본"}. 결과: ${set.outcome}. 통과 ${set.passed}/${set.ran}(기준 ${set.needed}), 건너뜀 ${set.skipped}, 오류 ${set.errors}.`,
    "",
    "| 문항 | 개념 | 결과 | 미달 까닭 |",
    "| --- | --- | --- | --- |",
    ...rows,
    "",
  ].join("\n");
}
