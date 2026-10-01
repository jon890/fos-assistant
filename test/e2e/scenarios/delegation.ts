/**
 * 도는 대화 turn 에서 MCP `agent_*` 도구로 다른 에이전트에게 일을 맡기고, 상태를 읽고, 멈추는 것을 한 번에 본다.
 *
 * <p>시나리오가 profile 플러그인 역할을 한다. 붙잡아 둔 turn 의 run 이 받은 session 을 뿌리로 `_fos_ctx` 를 계약대로
 * 서명해 `/mcp` 를 직접 부른다. 맡긴 실행은 뿌리 turn 의 자식으로 실행 나무에 남고, 사용자가 turn 을 멈추면 그 turn 이
 * 도는 동안 맡긴 실행도 함께 멈춘다(ADR-017).
 */
import { randomUUID } from "node:crypto";
import { call, expect, expectStatus, fail, step, type Scenario } from "../harness.ts";
import { awaitStatus, callTool, contextFor, openStream as openChatStream, parsed, tree, within, type Status, type Tree, type ToolResult } from "../delegation-support.ts";
import { AGENT_TOOLS_PROFILE } from "./agent-tools.ts";

export const DELEGATION_PROFILE = "delegation-group";

const AGENT_CODE = "delegation";

const INVALID_CONTEXT = "호출 맥락을 확인할 수 없습니다";

const openStream = (context: Parameters<Scenario["run"]>[0], text: string) => openChatStream(context, text, AGENT_CODE);

export const delegationScenario: Scenario = {
  name: "다른 에이전트에게 맡기기",

  async run(context) {
    step("관리자가 GROUP 에이전트를 새 profile 로 등록한다");
    expectStatus(
      await call(context, "/admin/agents", {
        method: "POST",
        token: context.tokens.dad,
        body: {
          code: AGENT_CODE,
          name: "Delegation",
          hermesProfile: DELEGATION_PROFILE,
          apiBaseUrl: `${context.hermesBaseUrl}/p/${DELEGATION_PROFILE}`,
          costMode: "SUBSCRIPTION",
          credentialScope: "SHARED_HOUSEHOLD",
          visibility: "GROUP",
          ownerEmail: null,
        },
      }),
      200,
      "위임 검사 에이전트 등록",
    );

    // 에이전트를 등록한 뒤로는 어디서 실패해도 정리한다. 정리가 실패해도 원래 실패를 가리지 않는다.
    const issuedIds: number[] = [];
    let rootExecutionId: number | undefined;
    let turnStopped = false;
    let failed = false;
    try {
      step("그 profile 에 묶은 토큰과 다른 profile 에 묶은 토큰을 발급한다");
      const issued = expectStatus(
        await call(context, "/admin/agent-tokens", {
          method: "POST",
          token: context.tokens.dad,
          body: { profileName: DELEGATION_PROFILE, label: "delegation-e2e" },
        }),
        200,
        "위임 검사 MCP 토큰 발급",
      ).json<{ id: number; token: string }>();
      issuedIds.push(issued.id);
      const otherIssued = expectStatus(
        await call(context, "/admin/agent-tokens", {
          method: "POST",
          token: context.tokens.dad,
          body: { profileName: AGENT_TOOLS_PROFILE, label: "delegation-other-e2e" },
        }),
        200,
        "다른 profile 의 MCP 토큰 발급",
      ).json<{ id: number; token: string }>();
      issuedIds.push(otherIssued.id);
      const token = issued.token;

      step("대화 turn 하나를 붙잡아 도는 뿌리 실행과 그 session 을 만든다");
      context.hermes.holdNextRun();
      const turn = await openStream(context, "위임 검사 turn");
      await within(context.hermes.waitForHeldRun(), 5_000, "가짜 Hermes 가 뿌리 turn 의 실행을 받지 않았다");
      rootExecutionId = await within(turn.executionId, 5_000, "뿌리 turn 의 started 사건을 받지 못했다");
      const rootRun = context.hermes.heldRun();
      if (rootRun === undefined) fail("붙잡은 뿌리 turn 의 run 이 없다");
      const rootSession = rootRun.sessionId;
      expect(rootSession.startsWith("fos-"), `뿌리 turn 의 session 이 fos- 로 시작하지 않는다: ${rootSession}`);

      const status = async (executionId: number): Promise<Status> =>
        parsed<Status>(
          await callTool(context, token, "agent_status", { execution_id: executionId }, contextFor(token, "agent_status", rootSession)),
          "agent_status",
        );
      const delegate = (task: string, toolCallId?: string, agentCode = AGENT_CODE): Promise<ToolResult> =>
        callTool(context, token, "agent_delegate", { agent_code: agentCode, task },
          contextFor(token, "agent_delegate", rootSession, toolCallId));

      step("agent_list 가 쓸 수 있는 켜진 에이전트만 준다");
      const listed = parsed<{ code: string; name: string }[]>(
        await callTool(context, token, "agent_list", {}, contextFor(token, "agent_list", rootSession)),
        "agent_list",
      );
      const codes = listed.map((agent) => agent.code);
      expect(codes.includes(AGENT_CODE), `맡길 수 있는 에이전트가 목록에 없다: ${codes}`);
      expect(!codes.includes("kid-tools"), `아이의 개인 에이전트가 아빠 목록에 있다: ${codes}`);
      expect(!codes.includes("native-delegation"), `꺼진 에이전트가 목록에 있다: ${codes}`);
      expect(
        listed.every((agent) => Object.keys(agent).join(",") === "code,name"),
        `목록 항목에 code 와 name 밖의 칸이 있다: ${JSON.stringify(listed)}`,
      );

      step("agent_delegate 가 바로 번호와 RUNNING 을 주고, 붙잡은 자식은 agent_status 가 RUNNING 이다");
      const firstCallId = `call_${randomUUID()}`;
      context.hermes.holdNextRun();
      const first = parsed<Status>(await delegate("첫째 맡긴 일", firstCallId), "첫째 agent_delegate");
      expect(first.status === "RUNNING", `첫째 위임의 상태가 RUNNING 이 아니다: ${JSON.stringify(first)}`);
      await within(context.hermes.waitForHeldRun(), 5_000, "가짜 Hermes 가 첫째 자식의 실행을 받지 않았다");
      expect(context.hermes.heldRun()?.sessionId !== rootSession, "자식이 뿌리 turn 의 session 으로 돈다");
      const firstStatus = await status(first.execution_id);
      expect(firstStatus.status === "RUNNING", `붙잡은 자식의 상태가 RUNNING 이 아니다: ${JSON.stringify(firstStatus)}`);

      step("자식을 끝내면 agent_status 가 SUCCEEDED 와 답을 준다");
      context.hermes.releaseHeldRun();
      const finished = await awaitStatus(() => status(first.execution_id), (value) => value.status !== "RUNNING", "첫째 자식");
      expect(finished.status === "SUCCEEDED", `첫째 자식이 SUCCEEDED 가 아니다: ${JSON.stringify(finished)}`);
      expect(finished.output?.includes("첫째 맡긴 일") === true, `첫째 자식의 답이 없다: ${JSON.stringify(finished)}`);

      step("다시 맡기고 agent_stop 하면 그 run 에 중지가 가고 CANCELLED 다");
      context.hermes.holdNextRun();
      const second = parsed<Status>(await delegate("멈출 일"), "둘째 agent_delegate");
      await within(context.hermes.waitForHeldRun(), 5_000, "가짜 Hermes 가 둘째 자식의 실행을 받지 않았다");
      const secondRun = context.hermes.heldRun();
      if (secondRun === undefined) fail("붙잡은 둘째 자식의 run 이 없다");
      const stopped = parsed<Status>(
        await callTool(context, token, "agent_stop", { execution_id: second.execution_id }, contextFor(token, "agent_stop", rootSession)),
        "agent_stop",
      );
      expect(stopped.status === "CANCELLED", `멈춘 자식의 상태가 CANCELLED 가 아니다: ${JSON.stringify(stopped)}`);
      expect(context.hermes.stoppedRuns().includes(secondRun.runId), "둘째 자식의 run 에 중지가 가지 않았다");
      const afterStop = await status(second.execution_id);
      expect(afterStop.status === "CANCELLED", `멈춘 뒤 agent_status 가 CANCELLED 가 아니다: ${JSON.stringify(afterStop)}`);

      step("끝난 실행의 agent_stop 은 끝난 상태를 그대로 준다");
      const stopFinished = parsed<Status>(
        await callTool(context, token, "agent_stop", { execution_id: first.execution_id }, contextFor(token, "agent_stop", rootSession)),
        "끝난 실행의 agent_stop",
      );
      expect(stopFinished.status === "SUCCEEDED", `끝난 실행의 상태가 바뀌었다: ${JSON.stringify(stopFinished)}`);

      step("서명을 바꾼 호출과 다른 profile 의 토큰은 호출 맥락 오류다");
      const tampered = contextFor(token, "agent_status", rootSession);
      tampered.sig = tampered.sig.slice(0, 63) + (tampered.sig.endsWith("0") ? "1" : "0");
      const tamperedResult = await callTool(context, token, "agent_status", { execution_id: first.execution_id }, tampered);
      expect(
        tamperedResult.isError && tamperedResult.text.startsWith(INVALID_CONTEXT),
        `서명을 바꾼 호출이 거절되지 않았다: ${tamperedResult.text}`,
      );
      const otherToken = otherIssued.token;
      const otherProfile = await callTool(context, otherToken, "agent_status", { execution_id: first.execution_id },
        contextFor(otherToken, "agent_status", rootSession));
      expect(
        otherProfile.isError && otherProfile.text.startsWith(INVALID_CONTEXT),
        `다른 profile 의 토큰으로 부른 호출이 거절되지 않았다: ${otherProfile.text}`,
      );

      step("없는 에이전트는 AGENT_UNAVAILABLE 이다");
      const missing = await delegate("없는 에이전트에게", undefined, "delegation-missing");
      expect(missing.isError, `없는 에이전트에게 맡겨졌다: ${missing.text}`);
      expect((JSON.parse(missing.text) as { code: string }).code === "AGENT_UNAVAILABLE", `실패 코드가 다르다: ${missing.text}`);

      step("같은 tool_call_id 로 다시 부르면 새 실행 없이 같은 번호를 준다");
      const again = parsed<Status>(await delegate("첫째 맡긴 일", firstCallId), "같은 호출의 agent_delegate");
      expect(again.execution_id === first.execution_id, `같은 호출에 다른 번호가 왔다: ${again.execution_id} != ${first.execution_id}`);

      step("실행 나무에 자식 둘이 뿌리 아래로 보이고 각자 토큰과 모델이 적혀 있다");
      const grown = await tree(context, rootExecutionId);
      expect(grown.root.executionId === rootExecutionId, `나무의 뿌리가 turn 이 아니다: ${grown.root.executionId}`);
      const childIds = grown.root.children.map((child) => child.executionId).sort((a, b) => a - b);
      const expectedIds = [first.execution_id, second.execution_id].sort((a, b) => a - b);
      expect(
        JSON.stringify(childIds) === JSON.stringify(expectedIds),
        `뿌리 아래 자식이 맡긴 둘과 다르다: ${JSON.stringify(childIds)} != ${JSON.stringify(expectedIds)}`,
      );
      for (const child of grown.root.children) {
        expect(
          child.model !== null && child.inputTokens !== null && child.outputTokens !== null,
          `자식 ${child.executionId} 에 토큰이나 모델이 없다: ${JSON.stringify(child)}`,
        );
      }

      step("사용자가 turn 을 멈추면 그 turn 이 도는 동안 맡긴 자식도 멈춘다");
      context.hermes.holdNextRun();
      const third = parsed<Status>(await delegate("turn 과 함께 멈출 일"), "셋째 agent_delegate");
      await within(context.hermes.waitForHeldRun(), 5_000, "가짜 Hermes 가 셋째 자식의 실행을 받지 않았다");
      const thirdRun = context.hermes.heldRun();
      if (thirdRun === undefined) fail("붙잡은 셋째 자식의 run 이 없다");
      expectStatus(
        await call(context, `/chat/executions/${rootExecutionId}/stop`, { method: "POST", token: context.tokens.dad }),
        202,
        "위임 검사 turn 중지",
      );
      turnStopped = true;
      await within(turn.completed, 10_000, "중지 뒤 뿌리 turn 의 스트림이 끝나지 않았다");
      expect(context.hermes.stoppedRuns().includes(thirdRun.runId), "turn 을 멈췄는데 셋째 자식의 run 에 중지가 가지 않았다");
      const deadline = Date.now() + 10_000;
      let last: Tree = await tree(context, rootExecutionId);
      while (Date.now() < deadline && last.root.children.find((child) => child.executionId === third.execution_id)?.status !== "CANCELLED") {
        await new Promise((resolve) => setTimeout(resolve, 100));
        last = await tree(context, rootExecutionId);
      }
      const thirdNode = last.root.children.find((child) => child.executionId === third.execution_id);
      expect(thirdNode?.status === "CANCELLED", `turn 과 함께 멈춘 자식이 CANCELLED 가 아니다: ${JSON.stringify(thirdNode)}`);
      expect(last.root.status === "CANCELLED", `멈춘 뿌리 turn 이 CANCELLED 가 아니다: ${last.root.status}`);
    } catch (error) {
      failed = true;
      throw error;
    } finally {
      const cleanupErrors: unknown[] = [];
      const cleanup = async (action: () => Promise<unknown>) => {
        try {
          await action();
        } catch (error) {
          cleanupErrors.push(error);
        }
      };
      // 중간에 실패했으면 붙잡힌 turn 이 뒤 시나리오에 남지 않게 멈춘다.
      if (rootExecutionId !== undefined && !turnStopped) {
        const executionId = rootExecutionId;
        await cleanup(() => call(context, `/chat/executions/${executionId}/stop`, { method: "POST", token: context.tokens.dad }));
        context.hermes.releaseHeldRun();
      }
      for (const tokenId of issuedIds) {
        await cleanup(async () => expectStatus(
          await call(context, `/admin/agent-tokens/${tokenId}`, { method: "DELETE", token: context.tokens.dad }),
          200,
          "위임 검사 MCP 토큰 폐기",
        ));
      }
      await cleanup(async () => expectStatus(
        await call(context, `/admin/agents/${AGENT_CODE}`, {
          method: "PATCH",
          token: context.tokens.dad,
          body: { enabled: false, visibility: "GROUP", ownerEmail: null },
        }),
        200,
        "위임 검사 에이전트 끄기",
      ));
      // finally 에서 던지면 원래 실패가 사라지므로, 원래 실패가 없을 때만 정리 실패를 알린다.
      if (!failed && cleanupErrors.length > 0) {
        fail(cleanupErrors.map((error) => (error instanceof Error ? error.message : String(error))).join("; "));
      }
    }
  },
};
