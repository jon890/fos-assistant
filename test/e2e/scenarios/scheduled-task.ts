/**
 * 예약 작업이 정한 시각에 발화해 작업 주인의 대화 turn 을 열고, 그 turn 의 쓰기 도구가 사람이 보낸 turn 과 같은 승인을
 * 거치는지 전체 흐름으로 본다.
 *
 * <p>승인이 필요한 커넥터 도구는 커넥터 도구 정책 시나리오와 같은 방법으로 부른다. 앞의 시나리오가 해제로 끝나므로 여기서
 * 다시 등록하고 끝에서 해제한다. 발화기는 `run.ts` 가 1초마다 돌게 해 두었다.
 */
import { call, expect, expectStatus, fail, step, type Context, type Scenario } from "../harness.ts";
import { CONNECTOR_TOOL_PROBE, DEMO_CONNECTOR, DEMO_TOKEN_OK } from "../fake-hermes.ts";

type ConnectionView = { status: string; agentCode: string | null };
type TaskView = { id: string; state: string; nextFireAt: string | null };
type TaskRunView = { id: string; status: string; reason: string | null; conversationId: string | null };
type ActionView = { actionId: string; status: string };
type Message = { id: number; role: "USER" | "ASSISTANT" | "SYSTEM"; content: string };
type NotificationView = { kind: string; targetType: string | null; targetId: string | null };

const CONNECTION = `/connections/${DEMO_CONNECTOR.id}`;
const PREFIX = `mcp__${DEMO_CONNECTOR.mcp_server}__`;
const TITLE = "예약 메모";
/** 발화한 줄이 끝나기를 기다리는 상한이다. */
const RUN_TIMEOUT_MS = 60_000;
/** 승인한 결과가 대화에 이어지기를 기다리는 상한이다. */
const DELIVERY_TIMEOUT_MS = 10_000;

/** 지금부터 몇 초 뒤의 UTC 시각을 시간대 없는 날짜와 시각(초까지)으로 쓴다. */
function utcLocalDateTimeAfter(seconds: number): string {
  const at = new Date(Math.ceil((Date.now() + seconds * 1000) / 1000) * 1000);
  return at.toISOString().slice(0, 19);
}

async function runsOf(context: Context, taskId: string): Promise<TaskRunView[]> {
  return expectStatus(
    await call(context, `/tasks/${taskId}/runs`, { token: context.tokens.dad }),
    200,
    "발화 기록",
  ).json<TaskRunView[]>();
}

async function messagesOf(context: Context, conversationId: string): Promise<Message[]> {
  return expectStatus(
    await call(context, `/chat/conversations/${conversationId}/messages`, { token: context.tokens.dad }),
    200,
    "예약 작업 대화의 이력",
  ).json<Message[]>();
}

export const scheduledTaskScenario: Scenario = {
  name: "예약 작업",

  async run(context) {
    context.hermes.setConnectorPolicy(`${context.api.replace(/\/api\/v1$/, "")}/internal/hermes/connector-policy`);
    let profile: string | undefined;
    let taskId: string | undefined;
    let failed = false;
    try {
      step("시험 커넥터를 다시 등록하고 연결을 확인해 READY 로 만든다");
      const requestsAtRegister = context.hermes.connectorRequests().length;
      expectStatus(
        await call(context, CONNECTION, {
          method: "POST", token: context.tokens.dad, body: { values: { token: DEMO_TOKEN_OK, scope: "a" } },
        }),
        200,
        "등록",
      );
      const installLine = context.hermes.connectorRequests().slice(requestsAtRegister)
        .find((line) => /^install \S+ on$/.test(line));
      expect(installLine !== undefined, "등록 동안 설치 요청이 없었다");
      profile = installLine!.split(" ")[1]!;
      const ready = expectStatus(
        await call(context, `${CONNECTION}/check`, { method: "POST", token: context.tokens.dad }), 200, "연결 확인",
      ).json<ConnectionView>();
      expect(ready.status === "READY" && ready.agentCode !== null, `READY 가 아니다: ${JSON.stringify(ready)}`);
      const agentCode = ready.agentCode!;

      step("그 커넥터 에이전트로 쓰기 도구를 부르는 한 번 도는 작업을 3초 뒤로 만든다");
      const hermesTool = `${PREFIX}write_note`;
      const created = expectStatus(
        await call(context, "/tasks", {
          method: "POST",
          token: context.tokens.dad,
          body: {
            title: TITLE,
            agentCode,
            instruction: `${CONNECTOR_TOOL_PROBE}\n${hermesTool} ${JSON.stringify({ text: "예약으로 남길 글" })}`,
            schedule: { type: "ONCE", fireAt: utcLocalDateTimeAfter(3), timeZone: "UTC" },
          },
        }),
        200,
        "작업 만들기",
      ).json<TaskView>();
      taskId = created.id;
      expect(created.nextFireAt !== null, `다음 실행 시각이 없다: ${JSON.stringify(created)}`);

      step("발화한 줄이 SUCCEEDED 가 되기를 기다린다");
      const deadline = Date.now() + RUN_TIMEOUT_MS;
      let finished: TaskRunView | undefined;
      let last: TaskRunView[] = [];
      while (finished === undefined && Date.now() < deadline) {
        last = await runsOf(context, taskId);
        finished = last.find((run) => run.status === "SUCCEEDED");
        if (finished === undefined) {
          const ended = last.find((run) => run.status === "FAILED" || run.status === "SKIPPED");
          if (ended !== undefined) fail(`발화가 성공하지 않고 끝났다: ${JSON.stringify(ended)}`);
          await new Promise((resolve) => setTimeout(resolve, 200));
        }
      }
      if (finished === undefined) {
        fail(`${RUN_TIMEOUT_MS / 1000}초 안에 발화가 SUCCEEDED 가 되지 않았다: ${JSON.stringify(last)}`);
      }
      const conversationId = finished.conversationId;
      expect(conversationId !== null, `발화 줄에 대화가 없다: ${JSON.stringify(finished)}`);

      step("그 대화에 승인을 기다리는 줄이 있다");
      const actions = expectStatus(
        await call(context, `/chat/conversations/${conversationId}/connector-actions`, { token: context.tokens.dad }),
        200,
        "승인 줄 목록",
      ).json<ActionView[]>();
      const pending = actions.find((action) => action.status === "PENDING");
      expect(pending !== undefined, `PENDING 승인 줄이 없다: ${JSON.stringify(actions)}`);

      step("알림 목록에 그 대화를 가리키는 APPROVAL_REQUESTED 와 TASK_SUCCEEDED 가 있다");
      const notifications = expectStatus(
        await call(context, "/notifications?limit=100", { token: context.tokens.dad }),
        200,
        "알림 목록",
      ).json<{ items: NotificationView[] }>().items;
      for (const kind of ["APPROVAL_REQUESTED", "TASK_SUCCEEDED"]) {
        expect(
          notifications.some(
            (item) => item.kind === kind && item.targetType === "CONVERSATION" && item.targetId === conversationId,
          ),
          `그 대화의 ${kind} 알림이 없다: ${JSON.stringify(notifications.slice(0, 5))}`,
        );
      }

      step("그 승인 줄을 승인하면 기존 승인 경로대로 실행되고 결과가 그 대화에 이어진다");
      const approved = expectStatus(
        await call(context, `/connector-actions/${pending!.actionId}/approve`, {
          method: "POST", token: context.tokens.dad, body: { grant: null },
        }),
        200,
        "승인",
      ).json<ActionView>();
      expect(approved.status === "SUCCEEDED", `승인한 줄이 SUCCEEDED 가 아니다: ${JSON.stringify(approved)}`);
      const deliveryDeadline = Date.now() + DELIVERY_TIMEOUT_MS;
      let history: Message[] = [];
      while (Date.now() < deliveryDeadline) {
        history = await messagesOf(context, conversationId!);
        if (history.length >= 5 && history.at(-2)!.role === "SYSTEM" && history.at(-1)!.role === "ASSISTANT") break;
        await new Promise((resolve) => setTimeout(resolve, 100));
      }
      expect(
        JSON.stringify(history.slice(0, 2).map((message) => [message.role, message.content]))
          === JSON.stringify([
            ["SYSTEM", `예약 작업 「${TITLE}」 을 시작했어요`],
            ["USER", `${CONNECTOR_TOOL_PROBE}\n${hermesTool} ${JSON.stringify({ text: "예약으로 남길 글" })}`],
          ]),
        `예약 turn 의 알림 줄과 지시가 다르다: ${JSON.stringify(history.map((m) => [m.role, m.content]))}`,
      );
      expect(
        history.length >= 5 && history.at(-2)!.role === "SYSTEM" && history.at(-1)!.role === "ASSISTANT",
        `승인한 결과가 대화에 이어지지 않았다: ${JSON.stringify(history.map((m) => [m.role, m.content]))}`,
      );

      step("발화 기록은 여전히 한 줄이다");
      const runs = await runsOf(context, taskId);
      expect(runs.length === 1, `발화 기록이 한 줄이 아니다: ${JSON.stringify(runs)}`);
    } catch (error) {
      failed = true;
      throw error;
    } finally {
      // 어디서 실패해도 대역과 연결과 작업을 되돌린다. 정리가 실패해도 원래 실패를 가리지 않는다.
      if (profile !== undefined) context.hermes.setPolicyHook(profile, true);
      try {
        if (taskId !== undefined) {
          expectStatus(
            await call(context, `/tasks/${taskId}`, { method: "DELETE", token: context.tokens.dad }), 204, "작업 지우기",
          );
        }
        expectStatus(await call(context, CONNECTION, { method: "DELETE", token: context.tokens.dad }), 200, "해제");
      } catch (cleanupError) {
        if (!failed) throw cleanupError;
      }
    }
  },
};
