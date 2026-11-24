/**
 * 묶음 평가를 가짜 Hermes 로 끝까지 돌린다(docs/features/agent-quality/design.md 의 「검증 전략」 REQ5, REQ15, REQ17).
 *
 * <p>러너가 묶음 폴더를 `test/e2e/fixtures/quality-bundles/` 로 바꾼다. 실행기를 고치지 않고 그 폴더의 묶음이 돈다(REQ5).
 * 가짜 Hermes 는 첫 문항에만 링크가 든 답을 주므로 하나는 통과하고 하나는 실패한다. 통과한 대화는 바로 지워지고 실패한 대화는 남는다.
 * 다른 사용자의 기억, 예약 작업, 대화 수는 평가 전후로 같다(REQ15).
 *
 * <p>평가 전용 사용자는 앞 시나리오(배포 뒤 확인)가 준비한 것을 쓴다. 그래서 그 뒤에 둔다. 승인 카드 처리는 평가 사용자에게 연결을 붙일 길이 이
 * 검사에 없어 `EvalRunnerTest` 가 본다.
 */
import { call, expect, expectStatus, step, type Context, type Scenario } from "../harness.ts";
import { TEST_SCRIPT_PATH } from "../fake-hermes/runtime-fixtures.ts";
import { connectEvaluationMcp } from "../quality-support.ts";

/** 러너가 Control Plane 에 주는 문항 상한이다. 답하지 않는 문항이 없어 넉넉하게 둔다. */
export const E2E_QUALITY_EVAL_TIMEOUT = "30s";

const PASS_PROMPT = "평가 e2e: 내 파일은 어디서 봐?";
const EVAL_WAIT_MS = 3 * 60_000;

type ItemView = { bundle: string; question: string; status: string; reasonCode: string | null; failures: string[]; conversationPublicId: string | null };
type BundleView = { id: string; status: string | null; passed: number; ran: number; skipped: number; errors: number };
type EvalView = {
  publicId: string;
  status: string;
  tier: string | null;
  cleaned: boolean;
  bundles: BundleView[];
  items: ItemView[];
  environmentError: boolean;
};

async function count(context: Context, path: string): Promise<number> {
  return expectStatus(await call(context, path, { token: context.tokens.dad }), 200, path).json<unknown[]>().length;
}


async function waitFinished(context: Context, publicId: string): Promise<EvalView> {
  const deadline = Date.now() + EVAL_WAIT_MS;
  while (Date.now() < deadline) {
    const view = expectStatus(await call(context, `/admin/quality/evals/${publicId}`, { token: context.tokens.dad }), 200, "평가 조회")
      .json<EvalView>();
    if (view.status !== "RUNNING") return view;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`평가가 ${EVAL_WAIT_MS}ms 안에 끝나지 않았다`);
}


export const evalRunScenario: Scenario = {
  name: "묶음 평가가 평가 사용자로 문항을 돌리고 통과한 대화를 지운다",

  async run(context) {
    step("테스트 묶음 폴더의 묶음이 목록에 보인다");
    const bundles = expectStatus(await call(context, "/admin/quality/eval-bundles", { token: context.tokens.dad }), 200, "묶음 목록")
      .json<{ id: string; questionCount: number }[]>();
    expect(bundles.some((bundle) => bundle.id === "e2e" && bundle.questionCount === 2), `e2e 묶음이 없다: ${JSON.stringify(bundles)}`);

    const response = await fetch(`${context.hermesBaseUrl}${TEST_SCRIPT_PATH}`, {
      method: "POST",
      body: JSON.stringify({ input: PASS_PROMPT, output: "내 파일은 [파일 공간](/files) 에서 봐요." }),
    });
    expect(response.ok, `가짜 Hermes 에 통과 문항의 답을 넣지 못했다: ${response.status}`);
    // 실패 문항이 환경 오류가 아니라 규칙 미달로 적히게 평가 profile 의 MCP 연결을 살려 둔다.
    await connectEvaluationMcp(context);
    const memoriesBefore = await count(context, "/memories");
    const tasksBefore = await count(context, "/tasks");
    const conversationsBefore = await count(context, "/chat/conversations");
    const followUpsBefore = await count(context, "/follow-ups");

    step("MEMBER 는 평가를 시작하지 못한다");
    expectStatus(await call(context, "/admin/quality/evals", { method: "POST", token: context.tokens.kid, body: {} }), 403, "MEMBER 의 평가 시작");

    step("평가를 시작하면 202 로 도는 줄이 오고, 다시 부르면 같은 줄이 200 으로 온다");
    const started = expectStatus(
      await call(context, "/admin/quality/evals", { method: "POST", token: context.tokens.dad, body: { bundles: ["e2e"] } }),
      202,
      "평가 시작",
    ).json<EvalView>();
    const again = await call(context, "/admin/quality/evals", { method: "POST", token: context.tokens.dad, body: { bundles: ["e2e"] } });
    if (again.status === 200) expect(again.json<EvalView>().publicId === started.publicId, "도는 평가가 있는데 새 줄을 만들었다");
    else expectStatus(again, 202, "앞 평가가 끝난 뒤의 새 평가");

    step("첫 문항만 통과하고 평가는 실패로 끝난다");
    const finished = await waitFinished(context, started.publicId);
    const byQuestion = new Map(finished.items.map((item) => [item.question, item]));
    expect(finished.status === "FAILED", `평가 결과가 다르다: ${JSON.stringify(finished)}`);
    const passed = byQuestion.get("files-link");
    expect(passed?.status === "PASSED", `통과 문항이 통과하지 못했다: ${JSON.stringify(passed)}`);
    expect(passed?.conversationPublicId === null, "통과한 문항의 대화가 결과에 남았다");
    const failed = byQuestion.get("memory-link");
    if (failed === undefined) throw new Error("실패 문항의 결과가 없다");
    expect(failed.status === "FAILED" && failed.failures.includes("MISSING_LINK:/memory"), `실패 문항의 까닭이 다르다: ${JSON.stringify(failed)}`);
    expect(finished.bundles[0]?.passed === 1 && finished.bundles[0]?.ran === 2, `묶음 결과가 다르다: ${JSON.stringify(finished.bundles)}`);
    expect(finished.cleaned, "평가가 만든 것을 다 비우지 못했다");
    expect(!finished.environmentError, `MCP 연결이 살아 있는데 환경 오류로 적었다: ${JSON.stringify(finished)}`);

    step("관리자는 남겨 둔 실패 대화를 읽고, MEMBER 는 읽지 못한다");
    const messagesPath = `/admin/quality/conversations/${failed.conversationPublicId}/messages`;
    const messages = expectStatus(await call(context, messagesPath, { token: context.tokens.dad }), 200, "실패 대화 읽기")
      .json<{ role: string; content: string }[]>();
    expect(messages.some((message) => message.role === "USER"), "실패 대화에 질문이 없다");
    expectStatus(await call(context, messagesPath, { token: context.tokens.kid }), 403, "MEMBER 의 실패 대화 읽기");

    step("다른 사용자의 기억, 예약 작업, 대화, 할 일은 바뀌지 않는다");
    expect(await count(context, "/memories") === memoriesBefore, "평가가 관리자의 기억 수를 바꿨다");
    expect(await count(context, "/tasks") === tasksBefore, "평가가 관리자의 예약 작업 수를 바꿨다");
    expect(await count(context, "/chat/conversations") === conversationsBefore, "평가가 관리자의 대화 수를 바꿨다");
    expect(await count(context, "/follow-ups") === followUpsBefore, "평가가 관리자의 할 일 수를 바꿨다");
  },
};
