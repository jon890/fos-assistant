// 블로그 사진 평가의 명령이다. `send` 는 시나리오대로 대화를 만들고 메시지를 보낸다.
// `score` 는 결과와 backend 로그를 읽어 판정하고 보고서를 쓴다. 계약은 docs/features/naver-blog-connector.md 의 「블로그 사진 평가」 가 갖는다.
// 토큰과 기본 주소는 표준 출력과 보고서에 쓰지 않는다. 대화 공개 식별자와 실행 번호만 쓴다.
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { parseBackendPipelineEvents } from "../../support/media-pipeline-log.ts";
import { ControlPlane, type ExecutionNode } from "./api.ts";
import {
  exitCode,
  previewOf,
  scoreRun,
  scoreScenario,
  type PhotoAnswer,
  type RunOutcome,
  type RunRecord,
  type RunScore,
} from "./score.ts";

type Message = { text: string; photos: string[] };
type Scenario = { id: string; messages: Message[] };
type RunsFile = { version: 1; startedAt: string; repeat: number; private: boolean; runs: RunRecord[] };

const DIR = dirname(fileURLToPath(import.meta.url));
const TIERS = ["FAST", "BALANCED", "DEEP"];
const DEFAULT_TURN_TIMEOUT_MS = 900000;
const BLOG_TOOL = "naver_blog";

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
  if (!Number.isSafeInteger(value) || value < 1)
    throw new UsageError("FOS_EVAL_TURN_TIMEOUT_MS 는 양의 정수여야 한다");
  return value;
}

function errorText(error: unknown): string {
  return error instanceof Error ? `${error.name}: ${error.message}` : String(error);
}

function readJson<T>(path: string): T {
  return JSON.parse(readFileSync(path, "utf8")) as T;
}

function loadAnswers(): Map<string, PhotoAnswer> {
  const photos = readJson<{ photos: { id: string; accept: string[] }[] }>(join(DIR, "photos", "photos.json")).photos;
  return new Map(photos.map((photo) => [photo.id, { id: photo.id, accept: photo.accept }]));
}

/** 시나리오와, 사진 id 를 파일 경로로 바꾸는 함수를 고른다. 비공개 재현은 시나리오 파일 옆의 상대 경로가 사진이다. */
function loadScenarios(ids: string[], scenarioFile: string | undefined) {
  if (scenarioFile) {
    const path = resolve(scenarioFile);
    return { scenarios: [readJson<Scenario>(path)], photoPath: (photo: string) => resolve(dirname(path), photo) };
  }
  const all = readJson<{ scenarios: Scenario[] }>(join(DIR, "scenarios.json")).scenarios;
  const unknown = ids.filter((id) => !all.some((scenario) => scenario.id === id));
  if (unknown.length > 0) throw new UsageError(`없는 시나리오다: ${unknown.join(", ")}`);
  const files = new Map(
    readJson<{ photos: { id: string; file: string }[] }>(join(DIR, "photos", "photos.json")).photos.map((p) => [p.id, p.file]),
  );
  return {
    scenarios: ids.length > 0 ? all.filter((scenario) => ids.includes(scenario.id)) : all,
    photoPath: (photo: string) => {
      const file = files.get(photo);
      if (!file) throw new UsageError(`사진 목록에 없는 id 다: ${photo}`);
      return join(DIR, "photos", file);
    },
  };
}

async function send(values: Record<string, unknown>): Promise<number> {
  const env = requireEnv(["FOS_EVAL_BASE_URL", "FOS_EVAL_TOKEN", "FOS_EVAL_AGENT_CODE", "FOS_EVAL_BLOG_CATEGORY"]);
  const tier = process.env.FOS_EVAL_TIER ?? "";
  if (tier && !TIERS.includes(tier)) throw new UsageError(`FOS_EVAL_TIER 는 ${TIERS.join(", ")} 가운데 하나여야 한다`);
  const repeat = Number(values.repeat ?? 3);
  if (!Number.isSafeInteger(repeat) || repeat < 1) throw new UsageError("--repeat 는 양의 정수여야 한다");
  const scenarioFile = values["scenario-file"] as string | undefined;
  const { scenarios, photoPath } = loadScenarios((values.scenario as string[] | undefined) ?? [], scenarioFile);
  const out = resolve(values.out as string);
  mkdirSync(out, { recursive: true });
  const api = new ControlPlane(env.FOS_EVAL_BASE_URL, env.FOS_EVAL_TOKEN, turnTimeout());
  const file: RunsFile = {
    version: 1,
    startedAt: new Date().toISOString(),
    repeat,
    private: Boolean(scenarioFile),
    runs: [],
  };
  // 중간에 멈춰도 앞 기록이 남도록 대화 번호를 받은 직후와 메시지를 보낼 때마다 다시 쓴다
  const save = () => writeFileSync(join(out, "runs.json"), `${JSON.stringify(file, null, 2)}\n`);
  for (const scenario of scenarios) {
    for (let n = 1; n <= repeat; n++) {
      const run: RunRecord = { scenarioId: scenario.id, repeat: n, conversationId: null, messages: [] };
      file.runs.push(run);
      try {
        const conversationId = await api.startConversation(env.FOS_EVAL_AGENT_CODE);
        run.conversationId = conversationId;
        save();
        if (tier) await api.chooseTier(conversationId, tier);
        for (const message of scenario.messages) {
          const sent = { executionId: null as number | null, attachmentIds: [] as number[], photoIds: [] as string[] };
          run.messages.push(sent);
          for (const photo of message.photos) {
            const path = photoPath(photo);
            sent.attachmentIds.push(await api.upload(conversationId, basename(path), readFileSync(path)));
            sent.photoIds.push(photo);
          }
          const text = message.text.replaceAll("{category}", env.FOS_EVAL_BLOG_CATEGORY);
          const reply = await api.send(conversationId, text, env.FOS_EVAL_AGENT_CODE, sent.attachmentIds);
          sent.executionId = reply.executionId;
          save();
        }
      } catch (error) {
        if (error instanceof UsageError) throw error;
        run.error = errorText(error);
      }
      save();
      const executions = run.messages.map((message) => message.executionId ?? "-").join(",");
      console.log(`${scenario.id} #${n} 대화 ${run.conversationId ?? "-"} 실행 ${executions}${run.error ? ` 오류 ${run.error}` : ""}`);
    }
  }
  const failed = file.runs.filter((run) => run.error).length;
  if (failed > 0) console.log(`오류로 멈춘 실행 ${failed}개. score 가 INCONCLUSIVE 로 판정한다`);
  return 0;
}

/** 루트에서 `children` 을 따라 모든 노드를 모은다. */
function nodesOf(root: ExecutionNode): ExecutionNode[] {
  return [root, ...(root.children ?? []).flatMap(nodesOf)];
}

type RunReport = RunRecord & RunScore & { kept: boolean; deleteError?: string };

async function judge(api: ControlPlane, run: RunRecord, answers: Map<string, PhotoAnswer> | null, log: ReturnType<typeof parseBackendPipelineEvents>): Promise<RunScore> {
  const blank = { events: log, answers, executionIds: [], status: null, failedTools: [], truncated: false, preview: null };
  const last = run.messages.at(-1);
  if (run.error || !run.conversationId || !last || last.executionId === null)
    return scoreRun({ ...blank, run: run.error ? run : { ...run, error: "보낸 기록 없음" } });
  try {
    const tree = await api.tree(last.executionId);
    const nodes = nodesOf(tree.root);
    const messages = await api.messages(run.conversationId);
    const reply = messages.find((message) => message.role === "ASSISTANT" && message.executionId === last.executionId);
    const htmls: string[] = [];
    for (const artifact of reply?.artifacts ?? []) {
      if (!artifact.deleted) htmls.push(await api.file(run.conversationId, artifact.path));
    }
    return scoreRun({
      run,
      answers,
      events: log,
      executionIds: nodes.map((node) => node.executionId),
      status: tree.root.status,
      failedTools: [...new Set(nodes.flatMap((node) =>
        (node.events ?? []).flatMap((event) =>
          event.toolName?.includes(BLOG_TOOL) && event.failed === true ? [event.toolName] : [],
        ),
      ))],
      truncated: tree.truncated,
      preview: previewOf(htmls, run.messages.flatMap((message) => message.attachmentIds)),
    });
  } catch (error) {
    return scoreRun({ ...blank, run: { ...run, error: `결과 조회 실패: ${errorText(error)}` } });
  }
}

function markdown(runs: RunReport[], scenarios: { scenarioId: string; outcome: RunOutcome; runs: number }[], repeat: number): string {
  const lines = ["# 블로그 사진 평가 결과", "", `반복 횟수: ${repeat}`, "", "| 시나리오 | 결과 | 실행 수 |", "| --- | --- | --- |"];
  for (const scenario of scenarios) lines.push(`| \`${scenario.scenarioId}\` | ${scenario.outcome} | ${scenario.runs} |`);
  const notPassed = runs.filter((run) => run.outcome !== "PASS");
  if (notPassed.length > 0) {
    lines.push("", "## 통과하지 못한 실행", "");
    for (const run of notPassed) {
      lines.push(`### \`${run.scenarioId}\` #${run.repeat} ${run.outcome}`, "");
      if (run.reasons.length > 0) lines.push(`까닭: ${run.reasons.join(", ")}`, "");
      for (const photo of run.photos.filter((p) => p.issues.length > 0))
        lines.push(`- ${photo.photoId} (첨부 ${photo.attachmentId}): ${photo.issues.join(", ")}`);
      lines.push("");
    }
  }
  const noted = runs.filter((run) => run.notes.length > 0);
  if (noted.length > 0) {
    lines.push("", "## 실행 참고", "");
    for (const run of noted)
      lines.push(`- \`${run.scenarioId}\` #${run.repeat} ${run.outcome}: 참고: ${run.notes.join(", ")}`);
    lines.push("");
  }
  const kept = runs.filter((run) => run.kept && run.conversationId);
  lines.push("## 남긴 대화", "");
  if (kept.length === 0) lines.push("없음");
  for (const run of kept)
    lines.push(`- \`${run.scenarioId}\` #${run.repeat}: ${run.conversationId}${run.deleteError ? ` (삭제 실패: ${run.deleteError})` : ""}`);
  return `${lines.join("\n")}\n`;
}

async function score(values: Record<string, unknown>): Promise<number> {
  if (!values.log) throw new UsageError("--log 가 필요하다");
  const env = requireEnv(["FOS_EVAL_BASE_URL", "FOS_EVAL_TOKEN"]);
  const out = resolve(values.out as string);
  const file = readJson<RunsFile>(join(out, "runs.json"));
  let log: ReturnType<typeof parseBackendPipelineEvents>;
  try {
    log = parseBackendPipelineEvents(readFileSync(resolve(values.log as string), "utf8"));
  } catch (error) {
    throw new UsageError(`로그 판독 실패: ${errorText(error)}`);
  }
  const answers = file.private ? null : loadAnswers();
  const api = new ControlPlane(env.FOS_EVAL_BASE_URL, env.FOS_EVAL_TOKEN, turnTimeout());
  const runs: RunReport[] = [];
  for (const run of file.runs) {
    const result: RunReport = { ...run, ...(await judge(api, run, answers, log)), kept: true };
    if (result.outcome === "PASS" && !values.keep && run.conversationId) {
      try {
        await api.deleteConversation(run.conversationId);
        result.kept = false;
      } catch (error) {
        result.deleteError = errorText(error);
      }
    }
    runs.push(result);
    console.log(`${run.scenarioId} #${run.repeat} ${result.outcome}`);
  }
  const ids = [...new Set(file.runs.map((run) => run.scenarioId))];
  const scenarios = ids.map((scenarioId) => {
    const outcomes = runs.filter((run) => run.scenarioId === scenarioId).map((run) => run.outcome);
    return { scenarioId, outcome: scoreScenario(outcomes, file.repeat), runs: outcomes.length };
  });
  writeFileSync(join(out, "report.json"), `${JSON.stringify({ repeat: file.repeat, private: file.private, scenarios, runs }, null, 2)}\n`);
  writeFileSync(join(out, "report.md"), markdown(runs, scenarios, file.repeat));
  return exitCode(scenarios.map((scenario) => scenario.outcome));
}

async function main(): Promise<number> {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: {
      out: { type: "string" },
      scenario: { type: "string", multiple: true },
      repeat: { type: "string" },
      "scenario-file": { type: "string" },
      log: { type: "string" },
      keep: { type: "boolean" },
    },
  });
  const command = positionals[0];
  if (command !== "send" && command !== "score") throw new UsageError("명령은 send 나 score 다");
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
