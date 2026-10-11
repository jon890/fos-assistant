/** 실제 Control Plane MCP와 TX를 fake Hermes 실행의 서명으로 왕복한다. */
import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { call, expect, expectStatus, fail, step, type Scenario } from "../harness.ts";
import { callTool, contextFor, within, type ToolResult } from "../delegation-support.ts";
import { NEW_PERSON } from "./people.ts";

export const CONTENT_DRAFT_PROFILE = "content-draft-mcp";
const AGENT = "content-draft-mcp";
const WRITE = "content_draft_write";
const READ = "content_draft_read";
const MARKER = "SYNTHETIC_DRAFT_E2E_PRIVATE_8361";
type DraftView = { draft: { draftId: string; revision: number; title: string }; status: string };

function data(result: ToolResult): DraftView {
  expect(!result.isError, `원고 도구 실패: ${result.text}`);
  const begin = "\n<external-data>\n";
  expect(result.text.includes(begin) && result.text.endsWith("</external-data>"), "원고 외부 데이터 경계가 없다");
  return JSON.parse(result.text.slice(result.text.indexOf(begin) + begin.length, result.text.lastIndexOf("</external-data>"))) as DraftView;
}

export const contentDraftMcpScenario: Scenario = {
  name: "원고 MCP 생성 재시도와 CAS·삭제",
  async run(context) {
    const ownerToken = context.tokens.aunt;
    const apiRoot = context.api.replace(/\/api\/v1$/, "");
    const writesBefore = context.hermes.connectorToolCalls().length;
    expectStatus(await call(context, "/admin/agents", { method: "POST", token: context.tokens.dad, body: {
      code: AGENT, name: "원고 검사", hermesProfile: CONTENT_DRAFT_PROFILE,
      apiBaseUrl: `${context.hermesBaseUrl}/p/${CONTENT_DRAFT_PROFILE}`, costMode: "SUBSCRIPTION",
      credentialScope: "DEDICATED", visibility: "PRIVATE", ownerEmail: NEW_PERSON.email,
    } }), 200, "원고 에이전트 등록");
    let tokenId: number | undefined;
    let conversationId: string | undefined;
    let completed: Promise<unknown> | undefined;
    try {
      const issued = expectStatus(await call(context, "/admin/agent-tokens", { method: "POST", token: context.tokens.dad,
        body: { profileName: CONTENT_DRAFT_PROFILE, label: "draft-e2e" } }), 200, "원고 토큰 발급").json<{ id: number; token: string }>();
      tokenId = issued.id;
      const token = issued.token;
      conversationId = expectStatus(await call(context, "/chat/conversations", { method: "POST", token: ownerToken,
        body: { agentCode: AGENT } }), 200, "원고 대화 시작").json<{ conversationId: string }>().conversationId;
      context.hermes.holdNextRun();
      const turn = call(context, "/chat/messages", { method: "POST", token: ownerToken,
        body: { conversationId, text: "원고 저장 검사", agentCode: AGENT } });
      completed = turn;
      turn.catch(() => undefined);
      await within(context.hermes.waitForHeldRun(), 5_000, "원고 실행을 받지 못했다");
      const session = context.hermes.heldRun()?.sessionId;
      if (session === undefined) fail("원고 session이 없다");
      const args = { requestId: randomUUID(), draft: { title: MARKER, category: "분류", tags: [], blocks: [] } };
      const fosCtx = contextFor(token, WRITE, session, `call_${randomUUID()}`);
      step("commit 응답을 downstream에서 끊고 같은 전송 및 새 전송으로 재호출한다");
      let committed = false;
      const proxy = createServer(async (_request, response) => {
        const upstream = await fetch(`${apiRoot}/mcp`, { method: "POST",
          headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
          body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: {
            name: WRITE, arguments: { ...args, _fos_ctx: fosCtx },
          } }),
        });
        const result = await upstream.json() as { result?: { isError?: boolean } };
        committed = upstream.status === 200 && result.result?.isError === false;
        response.destroy();
      });
      await new Promise<void>((resolve) => proxy.listen(0, "127.0.0.1", resolve));
      try {
        const address = proxy.address();
        if (address === null || typeof address === "string") fail("검사 프록시 주소가 없다");
        let disconnected = false;
        try { await fetch(`http://127.0.0.1:${address.port}/mcp`, { method: "POST", body: "drop response" }); }
        catch { disconnected = true; }
        expect(disconnected && committed, "원고 commit 뒤 실제 HTTP 응답 단절을 확인하지 못했다");
      } finally {
        await new Promise<void>((resolve, reject) => proxy.close((error) => error ? reject(error) : resolve()));
      }
      const first = data(await callTool(context, token, WRITE, args, fosCtx));
      const retry = data(await callTool(context, token, WRITE, args, contextFor(token, WRITE, session)));
      expect(first.draft.draftId === retry.draft.draftId && retry.draft.revision === 1, "응답 유실 재시도가 중복 생성됐다");
      const base = `/chat/conversations/${conversationId}/content-drafts`;
      const list = expectStatus(await call(context, base, { token: ownerToken }), 200, "원고 목록").json<{ items: unknown[] }>();
      expect(list.items.length === 1, "중복 원고가 남았다");
      const revised = data(await callTool(context, token, WRITE, { draftId: first.draft.draftId, expectedRevision: 1,
        draft: { ...args.draft, title: "수정 원고" } }, contextFor(token, WRITE, session)));
      expect(revised.draft.revision === 2, "CAS 수정이 새 판을 만들지 못했다");
      const current = data(await callTool(context, token, WRITE, args, contextFor(token, WRITE, session)));
      expect(current.draft.revision === 2 && current.draft.title === "수정 원고", "최초 재호출이 수정 본문을 되돌렸다");
      const read = data(await callTool(context, token, READ, { draftId: first.draft.draftId }, contextFor(token, READ, session)));
      expect(read.draft.revision === 2, "현재 원고 읽기가 다른 판을 반환했다");
      const conflict = await callTool(context, token, WRITE, { ...args, draft: { ...args.draft, title: "변조" } }, contextFor(token, WRITE, session));
      expect(conflict.isError && JSON.parse(conflict.text).currentRevision === 2 && !conflict.text.includes(MARKER), "본문 충돌이 안전한 typed 오류가 아니다");
      expect(context.hermes.connectorToolCalls().length === writesBefore, "원고 저장이 외부 서비스 도구를 호출했다");
      context.hermes.releaseHeldRun();
      expectStatus(await turn, 200, "원고 turn 완료");
      const history = expectStatus(await call(context, `/chat/conversations/${conversationId}/messages`, { token: ownerToken }), 200, "대화 이력");
      expect(!history.body.includes(MARKER), "원고 본문이 대화 이력에 남았다");
      expectStatus(await call(context, `/chat/conversations/${conversationId}`, { method: "DELETE", token: ownerToken }), 204, "원고 대화 삭제");
      expectStatus(await call(context, `${base}/${first.draft.draftId}`, { token: ownerToken }), 404, "삭제 대화 원고 차단");
    } finally {
      context.hermes.releaseHeldRun();
      if (completed) await within(completed, 5_000, "원고 실행 정리 실패");
      if (tokenId !== undefined) expectStatus(await call(context, `/admin/agent-tokens/${tokenId}`, { method: "DELETE", token: context.tokens.dad }), 200, "원고 토큰 폐기");
    }
  },
};
