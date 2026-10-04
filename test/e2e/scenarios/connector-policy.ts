/**
 * 연결용 에이전트의 커넥터 도구 호출이 Control Plane 의 판정을 거치는지 전체 흐름으로 본다.
 *
 * <p>대역이 profile 플러그인의 hook 처럼 도구 호출마다 판정을 묻고, 허용된 호출만 커넥터 서버에 닿은 것으로 친다.
 * 앞의 커넥터 연결 시나리오가 해제로 끝나므로 여기서 다시 등록하고 끝에서 해제한다.
 */
import { call, expect, expectStatus, fail, step, type Context, type Scenario } from "../harness.ts";
import { CONNECTOR_TOOL_PROBE, DEMO_CONNECTOR, DEMO_TOKEN_OK, type ConnectorToolCall } from "../fake-hermes.ts";

type ConnectionView = { status: string; agentCode: string | null };
type Turn = { conversationId: string; assistantText: string };
type ActionView = { actionId: string; status: string; resultText: string | null };
type Message = { id: number; role: "USER" | "ASSISTANT" | "SYSTEM"; content: string };
type Probe = { answer: string; conversationId: string };

const CONNECTION = `/connections/${DEMO_CONNECTOR.id}`;
/** `run.ts` 가 Control Plane 에 준 승인 대기 시간과 같아야 한다. */
const APPROVAL_TTL_MS = 15_000;
const PREFIX = `mcp__${DEMO_CONNECTOR.mcp_server}__`;

/** 연결용 에이전트에게 도구 호출 한 줄을 보내고 대역이 답한 판정 줄을 돌려준다. */
async function probe(context: Context, agentCode: string, hermesTool: string, argsJson: string): Promise<string> {
  return (await probeIn(context, agentCode, hermesTool, argsJson)).answer;
}

/** 판정 줄과 함께 그 호출이 나온 대화의 번호를 돌려준다. 승인 결과가 그 대화로 오는지 볼 때 쓴다. */
async function probeIn(context: Context, agentCode: string, hermesTool: string, argsJson: string): Promise<Probe> {
  const turn = expectStatus(
    await call(context, "/chat/messages", {
      method: "POST",
      token: context.tokens.dad,
      body: { text: `${CONNECTOR_TOOL_PROBE}\n${hermesTool} ${argsJson}`, agentCode },
    }),
    200,
    `${hermesTool} 호출 대화`,
  ).json<Turn>();
  const line = turn.assistantText.split("\n").find((candidate) => candidate.startsWith(`${hermesTool}: `));
  expect(line !== undefined, `답에 ${hermesTool} 의 판정 줄이 없다: ${turn.assistantText}`);
  return { answer: line!.slice(`${hermesTool}: `.length), conversationId: turn.conversationId };
}

async function messagesOf(context: Context, conversationId: string): Promise<Message[]> {
  return expectStatus(
    await call(context, `/chat/conversations/${conversationId}/messages`, { token: context.tokens.dad }),
    200,
    "승인 요청이 나온 대화의 이력 조회",
  ).json<Message[]>();
}

/** 대화의 메시지 목록을 조건이 참이 될 때까지 다시 읽는다. */
async function awaitMessages(
  context: Context,
  conversationId: string,
  predicate: (messages: Message[]) => boolean,
  timeoutMs: number,
  what: string,
): Promise<Message[]> {
  const deadline = Date.now() + timeoutMs;
  let last: Message[] = [];
  while (Date.now() < deadline) {
    last = await messagesOf(context, conversationId);
    if (predicate(last)) return last;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  fail(`${what}: ${timeoutMs / 1000}초 안에 기대한 메시지가 오지 않았다. 마지막 목록: ${JSON.stringify(last.map((m) => [m.role, m.content]))}`);
}

function requestNumber(answer: string): string | undefined {
  return /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/.exec(answer)?.[0];
}

export const connectorPolicyScenario: Scenario = {
  name: "커넥터 도구 정책",

  async run(context) {
    context.hermes.setConnectorPolicy(`${context.api.replace(/\/api\/v1$/, "")}/internal/hermes/connector-policy`);
    let profile: string | undefined;
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
      expect(
        (context.hermes.profileEnv(profile).MCP_FOS_ASSISTANT_API_KEY ?? "") !== "",
        "연결용 profile 의 env 에 MCP 토큰이 없다. hook 이 판정을 물을 때 쓰는 토큰이다",
      );
      const mine = (): ConnectorToolCall[] =>
        context.hermes.connectorToolCalls().filter((entry) => entry.profile === profile);
      expect(mine().length === 0, `호출하기 전인데 커넥터 서버에 닿은 호출이 있다: ${JSON.stringify(mine())}`);

      step("승인이 없는 읽기 도구는 허용되어 커넥터 서버에 닿는다");
      const listed = await probe(context, agentCode, `${PREFIX}list_scopes`, "{}");
      expect(listed === "allow", `list_scopes 가 허용되지 않았다: ${listed}`);
      expect(
        JSON.stringify(mine())
          === JSON.stringify([{ profile, hermesTool: `${PREFIX}list_scopes`, argsJson: "{}", via: "hook" }]),
        `list_scopes 호출 하나만 닿아야 한다: ${JSON.stringify(mine())}`,
      );

      step("승인이 필요한 쓰기 도구는 승인 요청 번호와 함께 막히고 커넥터 서버에 닿지 않는다");
      const noteArgs = JSON.stringify({ text: "안녕", tags: ["가", "나"] });
      const asked = await probeIn(context, agentCode, `${PREFIX}write_note`, noteArgs);
      const written = asked.answer;
      const actionId = requestNumber(written);
      expect(written.startsWith("block ") && actionId !== undefined, `write_note 가 승인 요청 번호와 함께 막히지 않았다: ${written}`);
      expect(mine().length === 1, `승인하기 전인데 write_note 가 커넥터 서버에 닿았다: ${JSON.stringify(mine())}`);

      step("다른 사용자는 그 승인 요청을 승인하지 못한다");
      const foreignApproval = expectStatus(
        await call(context, `/connector-actions/${actionId}/approve`, {
          method: "POST", token: context.tokens.kid, body: { grant: null },
        }),
        404,
        "다른 사용자의 승인",
      );
      expect(
        foreignApproval.json<{ code: string }>().code === "CONNECTOR_ACTION_NOT_FOUND",
        `오류 코드가 다르다\n${foreignApproval.body}`,
      );
      expect(mine().length === 1, `남이 승인한 호출이 커넥터 서버에 닿았다: ${JSON.stringify(mine())}`);

      step("주인이 승인하면 저장한 인자로 실행 경로를 한 번 부르고 결과를 줄에 남긴다");
      const approved = expectStatus(
        await call(context, `/connector-actions/${actionId}/approve`, {
          method: "POST", token: context.tokens.dad, body: { grant: null },
        }),
        200,
        "승인",
      ).json<ActionView>();
      expect(
        approved.status === "SUCCEEDED" && approved.actionId === actionId && approved.resultText !== null
          && JSON.stringify(JSON.parse(approved.resultText)) === JSON.stringify({ saved: true }),
        `승인한 줄이 SUCCEEDED 와 결과를 갖지 않는다: ${JSON.stringify(approved)}`,
      );
      const executed = mine().filter((entry) => entry.via === "execute");
      expect(executed.length === 1, `실행 경로의 호출이 하나가 아니다: ${JSON.stringify(mine())}`);
      expect(
        executed[0]!.hermesTool === `${PREFIX}write_note`
          && JSON.stringify(JSON.parse(executed[0]!.argsJson)) === JSON.stringify(JSON.parse(noteArgs)),
        `실행한 인자의 JSON 값이 보낸 것과 다르다: ${JSON.stringify(executed[0])}`,
      );

      step("승인한 결과가 그 요청이 나온 대화에 알림 줄로 남고 자동 turn 이 답을 잇는다");
      const delivered = await awaitMessages(
        context,
        asked.conversationId,
        (messages) => messages.length >= 2
          && messages.at(-2)!.role === "SYSTEM" && messages.at(-1)!.role === "ASSISTANT",
        10_000,
        "승인 결과의 알림 줄과 자동 turn 의 답",
      );
      const resultNotice = delivered.at(-2)!.content;
      expect(
        resultNotice.startsWith("승인한 ") && resultNotice.endsWith(" 실행이 끝났어요"),
        `결과 알림 줄의 글이 다르다: ${resultNotice}`,
      );
      expect(!resultNotice.includes("saved"), `알림 줄에 실행 결과의 본문이 실렸다: ${resultNotice}`);
      expect(
        delivered.filter((message) => message.role === "SYSTEM").length === 1,
        `알림 줄이 하나가 아니다: ${JSON.stringify(delivered.map((m) => [m.role, m.content]))}`,
      );
      const autoTurnInput = context.hermes.lastSubmittedInput() ?? "";
      expect(
        autoTurnInput.includes("승인한 동작의 결과가 도착했다.\n[출처: 승인한 동작, 동작: 메모 쓰기, 상태: SUCCEEDED")
          && !autoTurnInput.includes(actionId) && !autoTurnInput.includes("write_note") && autoTurnInput.includes("<external-data>"),
        `자동 turn 의 입력에 승인 결과가 실리지 않았다: ${autoTurnInput}`,
      );
      expect(mine().length === 2, `자동 turn 이 도구를 다시 실행했다: ${JSON.stringify(mine())}`);

      step("같은 승인을 다시 누르면 409 이고 실행은 한 번이다");
      const again = expectStatus(
        await call(context, `/connector-actions/${actionId}/approve`, {
          method: "POST", token: context.tokens.dad, body: { grant: null },
        }),
        409,
        "두 번째 승인",
      );
      expect(
        again.json<{ code: string }>().code === "CONNECTOR_ACTION_NOT_PENDING", `오류 코드가 다르다\n${again.body}`,
      );
      expect(mine().length === 2, `두 번째 승인이 다시 실행됐다: ${JSON.stringify(mine())}`);

      step("거절하면 실행하지 않고 그 대화에 알림 줄만 남기며 자동 turn 을 열지 않는다");
      const refusedAsk = await probeIn(context, agentCode, `${PREFIX}write_note`, JSON.stringify({ text: "거절할 글" }));
      const refusedId = requestNumber(refusedAsk.answer);
      expect(refusedAsk.answer.startsWith("block ") && refusedId !== undefined && refusedId !== actionId,
        `둘째 write_note 가 새 승인 요청 번호와 함께 막히지 않았다: ${refusedAsk.answer}`);
      const submitsBeforeReject = context.hermes.submitCount();
      const rejected = expectStatus(
        await call(context, `/connector-actions/${refusedId}/reject`, { method: "POST", token: context.tokens.dad }),
        200,
        "거절",
      ).json<ActionView>();
      expect(rejected.status === "REJECTED", `거절한 줄이 REJECTED 가 아니다: ${JSON.stringify(rejected)}`);
      const afterReject = await awaitMessages(
        context,
        refusedAsk.conversationId,
        (messages) => messages.at(-1)?.role === "SYSTEM",
        10_000,
        "거절의 알림 줄",
      );
      expect(afterReject.at(-1)!.content.endsWith(" 요청을 거절했어요"), `거절 알림 줄의 글이 다르다: ${afterReject.at(-1)!.content}`);
      // 자동 turn 이 열린다면 알림 줄 바로 뒤다. 잠시 기다려도 제출이 없고 답이 붙지 않아야 한다.
      await new Promise((resolve) => setTimeout(resolve, 1_000));
      expect(context.hermes.submitCount() === submitsBeforeReject, "거절이 자동 turn 을 열어 Hermes 에 제출했다");
      expect(
        (await messagesOf(context, refusedAsk.conversationId)).at(-1)!.role === "SYSTEM",
        "거절 알림 줄 뒤에 답이 붙었다",
      );
      expect(mine().length === 2, `거절한 호출이 커넥터 서버에 닿았다: ${JSON.stringify(mine())}`);

      step("답이 없는 승인 요청은 기다리는 시간이 지나면 만료 알림 줄만 남기고 그 뒤 승인은 409 다");
      const expiringAsk = await probeIn(context, agentCode, `${PREFIX}write_note`, JSON.stringify({ text: "기다릴 글" }));
      const expiringId = requestNumber(expiringAsk.answer);
      expect(expiringAsk.answer.startsWith("block ") && expiringId !== undefined,
        `셋째 write_note 가 승인 요청 번호와 함께 막히지 않았다: ${expiringAsk.answer}`);
      const submitsBeforeExpiry = context.hermes.submitCount();
      const afterExpiry = await awaitMessages(
        context,
        expiringAsk.conversationId,
        (messages) => messages.at(-1)?.role === "SYSTEM",
        APPROVAL_TTL_MS + 10_000,
        "만료의 알림 줄",
      );
      expect(
        afterExpiry.at(-1)!.content.endsWith(" 요청이 승인 없이 만료됐어요"),
        `만료 알림 줄의 글이 다르다: ${afterExpiry.at(-1)!.content}`,
      );
      await new Promise((resolve) => setTimeout(resolve, 1_000));
      expect(context.hermes.submitCount() === submitsBeforeExpiry, "만료가 자동 turn 을 열어 Hermes 에 제출했다");
      const lateApproval = expectStatus(
        await call(context, `/connector-actions/${expiringId}/approve`, {
          method: "POST", token: context.tokens.dad, body: { grant: null },
        }),
        409,
        "만료된 요청의 승인",
      );
      expect(
        lateApproval.json<{ code: string }>().code === "CONNECTOR_ACTION_NOT_PENDING",
        `오류 코드가 다르다\n${lateApproval.body}`,
      );
      expect(mine().length === 2, `만료된 호출이 커넥터 서버에 닿았다: ${JSON.stringify(mine())}`);

      step("선언하지 않은 도구와 파괴적인 도구는 글과 함께 막히고 커넥터 서버에 닿지 않는다");
      for (const tool of ["hidden_tool", "purge_notes"]) {
        const answer = await probe(context, agentCode, `${PREFIX}${tool}`, "{}");
        expect(answer.startsWith("block "), `${tool} 이 막히지 않았다: ${answer}`);
        expect(answer.slice("block ".length).trim() !== "", `${tool} 을 막은 글이 비었다`);
        expect(!answer.includes("정책을 확인하지 못했다"), `${tool} 을 Control Plane 의 판정이 아닌 까닭으로 막았다: ${answer}`);
      }
      expect(mine().length === 2, `막힌 호출이 커넥터 서버에 닿았다: ${JSON.stringify(mine())}`);

      step("다른 서버의 등록 이름은 선언한 도구와 이름이 같아도 막힌다");
      const foreign = await probe(context, agentCode, "mcp__other__list_scopes", "{}");
      expect(foreign.startsWith("block "), `다른 서버의 도구가 막히지 않았다: ${foreign}`);
      expect(mine().length === 2, `다른 서버의 호출이 커넥터 서버에 닿았다: ${JSON.stringify(mine())}`);

      step("정책 hook 이 꺼져 PENDING 이 되면 연결용 에이전트가 꺼져 대화가 거절되고 도구 호출이 없다");
      context.hermes.setPolicyHook(profile, false);
      const hookOff = expectStatus(
        await call(context, `${CONNECTION}/check`, { method: "POST", token: context.tokens.dad }), 200, "hook 이 꺼진 연결 확인",
      ).json<ConnectionView>();
      expect(hookOff.status === "PENDING", `hook 이 꺼졌는데 PENDING 이 아니다: ${JSON.stringify(hookOff)}`);
      const submitsBefore = context.hermes.submitCount();
      const refused = expectStatus(
        await call(context, "/chat/messages", {
          method: "POST",
          token: context.tokens.dad,
          body: { text: `${CONNECTOR_TOOL_PROBE}\n${PREFIX}list_scopes {}`, agentCode },
        }),
        409,
        "꺼진 연결용 에이전트와의 대화",
      );
      expect(refused.json<{ code: string }>().code === "AGENT_DISABLED", `오류 코드가 다르다\n${refused.body}`);
      expect(context.hermes.submitCount() === submitsBefore, "꺼진 에이전트의 대화가 Hermes 에 제출됐다");
      expect(mine().length === 2, `PENDING 인 연결의 호출이 커넥터 서버에 닿았다: ${JSON.stringify(mine())}`);
    } catch (error) {
      failed = true;
      throw error;
    } finally {
      // 어디서 실패해도 대역과 연결을 되돌린다. 정리가 실패해도 원래 실패를 가리지 않는다.
      if (profile !== undefined) context.hermes.setPolicyHook(profile, true);
      try {
        expectStatus(await call(context, CONNECTION, { method: "DELETE", token: context.tokens.dad }), 200, "해제");
      } catch (cleanupError) {
        if (!failed) throw cleanupError;
      }
    }
  },
};
