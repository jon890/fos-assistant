/**
 * 배포 뒤 확인을 가짜 Hermes 로 끝까지 돌린다(docs/features/agent-quality/design.md 의 「검증 전략」 REQ1, REQ3).
 *
 * <p>관리자가 평가 전용 사용자를 준비하고 확인을 시작하면 기능이 차례로 돈다. 가짜 Hermes 는 기억 읽기 문항에만 미리 넣은 사실을
 * 답하므로 그 기능만 통과하고, 도구를 부르지 않는 나머지는 실패한다. 예약 작업 발화는 기능 마감(러너가 줄여 준다)보다 늦게 발화해
 * 시간 초과로 끝난다. 도구 찾기는 가짜 Hermes 가 `task_list` 를 부르지 않아 실패한다. 확인은 실패로 끝나고 관리자에게 알림이 간다. 다른 사용자의 기억과
 * 예약 작업은 바뀌지 않는다. 시작 전에 평가 profile 의 MCP 연결을 살려 두어 실패가 환경 오류가 아니라 기능의 까닭으로 적힌다.
 *
 * <p>평가 사용자를 하나 더 만들어 앞 시나리오의 사람 수 세기를 깨지 않도록 맨 끝에 둔다.
 */
import { call, expect, expectStatus, step, type Context, type Scenario } from "../harness.ts";
import { TEST_SCRIPT_PATH } from "../fake-hermes/runtime-fixtures.ts";
import { connectEvaluationMcp } from "../quality-support.ts";

/** 러너가 Control Plane 에 주는 기능 마감이다. 예약 작업 발화는 1분 넘게 뒤라 이 안에 끝나지 않는다. */
export const E2E_QUALITY_FEATURE_TIMEOUT = "20s";

/** `DeployCheckItems.MEMORY_PROMPT` 와 같은 글이다. 가짜 Hermes 가 이 입력에만 미리 넣은 사실을 답한다. */
const MEMORY_PROMPT = "내 기억에 적힌 것을 보고, 평가 사용자가 좋아하는 색이 무엇인지 알려 주세요.";

const CHECK_WAIT_MS = 5 * 60_000;

type FeatureResult = {
  feature: string;
  status: string;
  reasonCode: string | null;
  conversationPublicId: string | null;
  executionIds: number[];
};
type CheckView = {
  publicId: string;
  status: string;
  trigger: string;
  itemCount: number;
  results: FeatureResult[];
  environmentError: boolean;
};
type NotificationPage = { items: { kind: string; targetType: string | null; body: string }[] };

async function count(context: Context, path: string): Promise<number> {
  return expectStatus(await call(context, path, { token: context.tokens.dad }), 200, path).json<unknown[]>().length;
}


async function waitFinished(context: Context, publicId: string): Promise<CheckView> {
  const deadline = Date.now() + CHECK_WAIT_MS;
  while (Date.now() < deadline) {
    const view = expectStatus(
      await call(context, `/admin/quality/checks/${publicId}`, { token: context.tokens.dad }),
      200,
      "확인 조회",
    ).json<CheckView>();
    if (view.status !== "RUNNING") return view;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`배포 뒤 확인이 ${CHECK_WAIT_MS}ms 안에 끝나지 않았다`);
}


export const deployCheckScenario: Scenario = {
  name: "배포 뒤 확인이 기능을 차례로 돌려 실패를 알린다",

  async run(context) {
    step("평가 사용자가 없으면 확인을 시작하지 못한다");
    const missing = expectStatus(
      await call(context, "/admin/quality/checks", { method: "POST", token: context.tokens.dad }),
      409,
      "평가 사용자 없이 시작",
    );
    expect(missing.body.includes("EVALUATION_USER_MISSING"), `오류 코드가 다르다: ${missing.body}`);

    step("관리자가 평가 사용자를 준비한다");
    expectStatus(
      await call(context, "/admin/quality/evaluation-user", {
        method: "POST",
        token: context.tokens.dad,
        body: { hermesProfile: "evaluation" },
      }),
      200,
      "평가 사용자 준비",
    );
    const response = await fetch(`${context.hermesBaseUrl}${TEST_SCRIPT_PATH}`, {
      method: "POST",
      body: JSON.stringify({ input: MEMORY_PROMPT, output: "평가 사용자가 좋아하는 색은 청록이에요." }),
    });
    expect(response.ok, `가짜 Hermes 에 기억 읽기 답을 넣지 못했다: ${response.status}`);
    await connectEvaluationMcp(context);
    const memoriesBefore = await count(context, "/memories");
    const tasksBefore = await count(context, "/tasks");

    step("MEMBER 는 확인을 시작하지 못한다");
    expectStatus(
      await call(context, "/admin/quality/checks", { method: "POST", token: context.tokens.kid }),
      403,
      "MEMBER 의 확인 시작",
    );

    step("확인을 시작하면 202 로 도는 줄이 오고, 다시 부르면 같은 줄이 200 으로 온다");
    const started = expectStatus(
      await call(context, "/admin/quality/checks", { method: "POST", token: context.tokens.dad }),
      202,
      "확인 시작",
    ).json<CheckView>();
    const again = expectStatus(
      await call(context, "/admin/quality/checks", { method: "POST", token: context.tokens.dad }),
      200,
      "도는 확인 다시 시작",
    ).json<CheckView>();
    expect(again.publicId === started.publicId, "도는 확인이 있는데 새 줄을 만들었다");

    step("기억 읽기만 통과하고 확인은 실패로 끝난다");
    const finished = await waitFinished(context, started.publicId);
    const byFeature = new Map(finished.results.map((result) => [result.feature, result]));
    expect(finished.status === "FAILED", `확인 결과가 다르다: ${JSON.stringify(finished)}`);
    expect(finished.itemCount === 8, `돈 기능 수가 다르다: ${finished.itemCount}`);
    expect(!finished.environmentError, `MCP 연결이 살아 있는데 환경 오류로 적었다: ${JSON.stringify(finished)}`);
    const memory = byFeature.get("MEMORY_READ");
    expect(memory?.status === "PASSED", `기억 읽기가 통과하지 못했다: ${JSON.stringify(memory)}`);
    expect(memory?.conversationPublicId === null, "통과한 기능의 대화가 결과에 남았다");
    const search = byFeature.get("SEARCH");
    if (search === undefined) throw new Error("검색 확인 결과가 없다");
    expect(search.reasonCode === "NO_SEARCH", `검색 확인의 까닭이 다르다: ${JSON.stringify(search)}`);
    expect(search.conversationPublicId !== null && search.executionIds.length === 1, "실패한 기능의 대화와 실행이 남지 않았다");
    expect(byFeature.get("DELEGATION")?.reasonCode === "NO_CHILD", "위임 확인의 까닭이 다르다");
    expect(byFeature.get("TOOL_DISCOVERY")?.reasonCode === "NO_TOOL", `도구 찾기의 까닭이 다르다: ${JSON.stringify(byFeature.get("TOOL_DISCOVERY"))}`);
    expect(byFeature.get("TASK_FIRE")?.status === "TIMED_OUT", `예약 작업 발화가 시간 초과가 아니다: ${JSON.stringify(byFeature.get("TASK_FIRE"))}`);

    step("관리자는 실패한 평가 대화의 실행 트리를 열고, MEMBER 는 열지 못한다");
    const treePath = `/admin/quality/executions/${search.executionIds[0]}/tree`;
    expectStatus(await call(context, treePath, { token: context.tokens.dad }), 200, "관리자 트리 조회");
    expectStatus(await call(context, treePath, { token: context.tokens.kid }), 403, "MEMBER 트리 조회");

    step("관리자에게 품질 화면으로 가는 실패 알림이 간다");
    const page = expectStatus(await call(context, "/notifications", { token: context.tokens.dad }), 200, "알림 목록")
      .json<NotificationPage>();
    const alert = page.items.find((item) => item.kind === "QUALITY_CHECK_FAILED");
    if (alert === undefined) throw new Error(`실패 알림이 없다: ${JSON.stringify(page.items.slice(0, 3))}`);
    expect(alert.targetType === "ADMIN_QUALITY", `실패 알림이 없다: ${JSON.stringify(page.items.slice(0, 3))}`);
    expect(alert.body.includes("검색과 출처") && !alert.body.includes("기억 읽기"), `알림 본문이 다르다: ${alert.body}`);

    step("다른 사용자의 기억과 예약 작업은 바뀌지 않는다");
    expect(await count(context, "/memories") === memoriesBefore, "확인이 관리자의 기억 수를 바꿨다");
    expect(await count(context, "/tasks") === tasksBefore, "확인이 관리자의 예약 작업 수를 바꿨다");
  },
};
