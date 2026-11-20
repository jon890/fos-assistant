// 서비스 개념 평가의 명령이다. `send` 는 문항마다 새 대화를 만들어 보내고, `score` 는 답과 실행 트리를 읽어 판정하고 보고서를 쓴다.
// 계약은 docs/features/service-concepts/design.md 의 「평가 세트」 가 갖는다. 틀은 test/model-eval/answer/run.ts 와 같다.
// 토큰과 기본 주소, 답 본문은 표준 출력과 결과 파일에 쓰지 않는다. 대화 공개 식별자와 실행 번호만 쓴다.
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { ControlPlane, TIERS } from "../control-plane.ts";
import {
  exitCode,
  failedQuestion,
  fill,
  markdown,
  scoreQuestion,
  scoreSet,
  skippedQuestion,
  type Question,
  type QuestionScore,
  type Values,
} from "./score.ts";

type RunRecord = {
  questionId: string;
  skipped: boolean;
  conversationId: string | null;
  executionIds: (number | null)[];
  startedAt: string;
  error?: string;
};
type RunsFile = { version: 1; startedAt: string; label: string; tier: string; runs: RunRecord[] };

const DIR = dirname(fileURLToPath(import.meta.url));
const DEFAULT_TURN_TIMEOUT_MS = 300000;
const USAGE = `사용법:
  node test/model-eval/concepts/run.ts send --out <결과 디렉터리> [--question <id>]... [--tier <칸>] [--label <이름표>]
  node test/model-eval/concepts/run.ts score --out <결과 디렉터리> [--keep]

환경 변수: FOS_EVAL_BASE_URL, FOS_EVAL_TOKEN, FOS_EVAL_AGENT_CODE (send 는 셋 모두, score 는 앞의 둘)
문항의 자리: FOS_EVAL_OTHER_AGENT_CODE, FOS_EVAL_OTHER_AGENT_NAME, FOS_EVAL_CONNECTOR_ID, FOS_EVAL_CONNECTOR_TITLE (send 와 score 에 같은 값)
  값이 빈 자리를 쓰는 문항은 돌리지 않고 「건너뜀」 으로 적는다
선택: FOS_EVAL_TIER (--tier 가 없을 때), FOS_EVAL_TURN_TIMEOUT_MS
전제: 평가 에이전트는 평가 계정이 주인인 비공개 에이전트다. 다른 에이전트는 그 계정이 쓸 수 있고, 플러그인은 그 계정이 연결하지 않은 것이다
score 는 통과한 대화를 지운다. --keep 은 모두 남긴다.
종료 코드: 0 통과, 1 기준 미달, 2 사용법 오류나 보내기·읽기 오류`;

class UsageError extends Error {}

function requireEnv(names: string[]): Record<string, string> {
  const missing = names.filter((name) => !process.env[name]);
  if (missing.length > 0) throw new UsageError(`환경 변수가 비어 있다: ${missing.join(", ")}`);
  return Object.fromEntries(names.map((name) => [name, process.env[name] as string]));
}

function turnTimeout(): number {
  const raw = process.env.FOS_EVAL_TURN_TIMEOUT_MS;
  if (!raw) return DEFAULT_TURN_TIMEOUT_MS;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < 1) throw new UsageError("FOS_EVAL_TURN_TIMEOUT_MS 는 양의 정수여야 한다");
  return value;
}

function slotValues(): Values {
  return {
    otherAgentCode: process.env.FOS_EVAL_OTHER_AGENT_CODE,
    otherAgentName: process.env.FOS_EVAL_OTHER_AGENT_NAME,
    connectorId: process.env.FOS_EVAL_CONNECTOR_ID,
    connectorTitle: process.env.FOS_EVAL_CONNECTOR_TITLE,
  };
}

function errorText(error: unknown): string {
  return error instanceof Error ? `${error.name}: ${error.message}` : String(error);
}

function readJson<T>(file: string): T {
  return JSON.parse(readFileSync(file, "utf8")) as T;
}

function loadQuestions(ids: string[]): Question[] {
  const all = readJson<{ questions: Question[] }>(join(DIR, "questions.json")).questions;
  const unknown = ids.filter((id) => !all.some((question) => question.id === id));
  if (unknown.length > 0) throw new UsageError(`없는 문항이다: ${unknown.join(", ")}`);
  return ids.length > 0 ? all.filter((question) => ids.includes(question.id)) : all;
}

async function send(values: Record<string, unknown>): Promise<number> {
  const env = requireEnv(["FOS_EVAL_BASE_URL", "FOS_EVAL_TOKEN", "FOS_EVAL_AGENT_CODE"]);
  const tier = (values.tier as string | undefined) ?? process.env.FOS_EVAL_TIER ?? "";
  if (tier && !TIERS.includes(tier)) throw new UsageError(`모델 칸은 ${TIERS.join(", ")} 가운데 하나여야 한다`);
  const questions = loadQuestions((values.question as string[] | undefined) ?? []);
  const slots = slotValues();
  const out = resolve(values.out as string);
  mkdirSync(out, { recursive: true });
  const api = new ControlPlane(env.FOS_EVAL_BASE_URL, env.FOS_EVAL_TOKEN, turnTimeout());
  const file: RunsFile = { version: 1, startedAt: new Date().toISOString(), label: (values.label as string | undefined) ?? "", tier, runs: [] };
  // 중간에 멈춰도 앞 기록이 남도록 대화 번호를 받은 직후와 메시지를 보낼 때마다 다시 쓴다
  const save = () => writeFileSync(join(out, "runs.json"), `${JSON.stringify(file, null, 2)}\n`);
  let failed = false;
  for (const original of questions) {
    const question = fill(original, slots);
    const run: RunRecord = {
      questionId: original.id,
      skipped: question === null,
      conversationId: null,
      executionIds: [],
      startedAt: new Date().toISOString(),
    };
    file.runs.push(run);
    if (question) {
      try {
        const conversationId = await api.startConversation(env.FOS_EVAL_AGENT_CODE);
        run.conversationId = conversationId;
        save();
        if (tier) await api.chooseTier(conversationId, tier);
        for (const text of question.messages) {
          const reply = await api.send(conversationId, text, env.FOS_EVAL_AGENT_CODE, []);
          run.executionIds.push(reply.executionId);
          save();
        }
      } catch (error) {
        run.error = errorText(error);
        failed = true;
      }
    }
    save();
    console.log(
      `${original.id} ${run.skipped ? "건너뜀" : `대화 ${run.conversationId ?? "-"} 실행 ${run.executionIds.map((id) => id ?? "-").join(",") || "-"}`}${run.error ? ` 오류 ${run.error}` : ""}`,
    );
  }
  return failed ? 2 : 0;
}

async function score(values: Record<string, unknown>): Promise<number> {
  const env = requireEnv(["FOS_EVAL_BASE_URL", "FOS_EVAL_TOKEN"]);
  const out = resolve(values.out as string);
  const file = readJson<RunsFile>(join(out, "runs.json"));
  const questions = new Map(loadQuestions([]).map((question) => [question.id, question]));
  const slots = slotValues();
  const api = new ControlPlane(env.FOS_EVAL_BASE_URL, env.FOS_EVAL_TOKEN, turnTimeout());
  const scores: QuestionScore[] = [];
  for (const run of file.runs) {
    const original = questions.get(run.questionId);
    if (!original) throw new UsageError(`결과 파일의 문항이 질문 세트에 없다: ${run.questionId}`);
    const question = fill(original, slots);
    let result: QuestionScore;
    if (run.skipped || !question) {
      result = skippedQuestion(original);
    } else {
      try {
        if (run.error) throw new Error(run.error);
        // 마지막으로 보낸 turn 의 루트 실행에 묶인 답만 본다
        const executionId = run.executionIds.at(-1);
        if (!run.conversationId || executionId == null) throw new Error("보낸 실행 번호가 없다");
        const messages = await api.messages(run.conversationId);
        const answer = messages.findLast((message) => message.role === "ASSISTANT" && message.executionId === executionId);
        if (!answer?.content) throw new Error("그 실행의 답을 찾지 못했다");
        const tree = await api.tree(executionId);
        result = scoreQuestion(question, answer.content, tree.root);
      } catch (error) {
        if (error instanceof UsageError) throw error;
        result = failedQuestion(original, errorText(error));
      }
    }
    scores.push(result);
    // 통과하지 못한 문항의 대화는 사람이 읽도록 남긴다
    if (run.conversationId && !values.keep && result.passed) {
      try {
        await api.deleteConversation(run.conversationId);
      } catch (error) {
        console.error(`${run.questionId} 대화를 지우지 못했다: ${errorText(error)}`);
      }
    }
    console.log(`${run.questionId} ${result.skipped ? "건너뜀" : result.error ? `오류 ${result.error}` : result.passed ? "통과" : "미달"}`);
  }
  const set = scoreSet(scores);
  writeFileSync(join(out, "report.json"), `${JSON.stringify({ label: file.label, tier: file.tier, set, scores }, null, 2)}\n`);
  writeFileSync(join(out, "report.md"), markdown(file.label, file.tier, scores, set));
  console.log(`세트 결과: ${set.outcome} (통과 ${set.passed}/${set.ran}, 기준 ${set.needed})`);
  return exitCode(set.outcome);
}

async function main(): Promise<number> {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: {
      help: { type: "boolean" },
      out: { type: "string" },
      question: { type: "string", multiple: true },
      tier: { type: "string" },
      label: { type: "string" },
      keep: { type: "boolean" },
    },
  });
  if (values.help) {
    console.log(USAGE);
    return 0;
  }
  const command = positionals[0];
  if (command !== "send" && command !== "score") throw new UsageError(`명령은 send 나 score 다\n\n${USAGE}`);
  if (!values.out) throw new UsageError("--out 이 필요하다");
  return command === "send" ? send(values) : score(values);
}

main().then(
  (code) => process.exit(code),
  // 인자와 환경 변수가 틀렸거나 판정까지 가지 못했으면 2 로 끝난다
  (error: unknown) => {
    console.error(error instanceof UsageError ? error.message : errorText(error));
    process.exit(2);
  },
);
