import {
  CONNECTOR_TOOL_PROBE,
  DEMO_CONNECTOR,
} from "../../../test/e2e/fake-hermes.ts";
import type { Page } from "../../node_modules/@playwright/test/index.js";
import {
  bindDemoConnector,
  connectDemoConnector,
  disconnectDemoConnector,
  expect,
  setSession,
  test,
} from "./fixtures.ts";
import { openConversationWith, openTodo, restorePrimaries } from "./helpers.ts";
import type { AgentConnectionsView } from "../../../test/e2e/connector-support.ts";

const tool = `mcp__${DEMO_CONNECTOR.mcp_server}__write_note`;

test.skip(!process.env.BROWSER_CONNECTOR_CALLS_LOG, "탐색 QA 실행 명령으로 도구 호출 기록을 남긴다.");

async function waitForBinding(page: Page, code: string) {
  await expect
    .poll(async () => {
      const response = await page.request.get(`/api/agents/${code}/connections`);
      expect(response.ok()).toBeTruthy();
      const { connections } = (await response.json()) as AgentConnectionsView;
      return connections.find(({ connectorId }) => connectorId === DEMO_CONNECTOR.id)
        ?.status;
    }, { timeout: 30_000 })
    .toBe("READY");
}

for (const decision of ["approve", "reject"] as const) {
  test(`탐색 QA: 새로고침한 승인 카드에서 ${decision === "approve" ? "승인 연속 클릭" : "거절"}`, async ({
    page,
    context,
    isolatedMember,
  }, testInfo) => {
    test.setTimeout(60_000);
    await setSession(context, isolatedMember);
    expect((await page.request.get("/api/me")).ok()).toBeTruthy();
    await connectDemoConnector(isolatedMember.email);
    let agentCode: string | undefined;

    try {
      const created = await page.request.post("/api/agents", {
        data: { name: "승인 검사용 비서" },
      });
      expect(created.status()).toBe(201);
      const { code } = (await created.json()) as { code: string };
      agentCode = code;
      await bindDemoConnector(isolatedMember.email, code);
      await waitForBinding(page, code);

      const conversationId = await openConversationWith(page, code, isolatedMember.email);
      const note = `탐색 QA ${decision} ${testInfo.project.name} ${testInfo.repeatEachIndex}`;
      await page.getByRole("textbox", { name: "메시지" }).fill(
        `${CONNECTOR_TOOL_PROBE}\n${tool} ${JSON.stringify({ text: note })}`,
      );
      await page.getByRole("button", { name: "보내기" }).click();
      await openTodo(page);
      const card = page.getByTestId("approval-card");
      await expect(card).toContainText(note);

      const actionsUrl = `/api/chat/conversations/${conversationId}/connector-actions`;
      const actions = async () => {
        const response = await page.request.get(actionsUrl);
        expect(response.ok()).toBeTruthy();
        return (await response.json()) as { actionId: string; status: string }[];
      };
      const [pending] = await actions();
      expect(pending?.status).toBe("PENDING");

      await page.reload();
      await openTodo(page);
      await expect(card).toContainText(note);
      await expect(card).toHaveAttribute("data-status", "PENDING");
      expect(await actions()).toEqual(expect.arrayContaining([pending]));
      await page.screenshot({ path: testInfo.outputPath("pending-after-reload.png") });

      const decisionPath = `/api/connector-actions/${pending!.actionId}/${decision}`;
      const requests: string[] = [];
      page.on("request", (request) => {
        if (request.method() === "POST" && new URL(request.url()).pathname === decisionPath) {
          requests.push(request.method());
        }
      });
      const [response] = await Promise.all([
        page.waitForResponse((reply) =>
          reply.request().method() === "POST" && new URL(reply.url()).pathname === decisionPath),
        card.getByTestId(`approval-${decision}`).click({
          clickCount: decision === "approve" ? 2 : 1,
        }),
      ]);
      expect(response.status()).toBe(200);
      const status = decision === "approve" ? "SUCCEEDED" : "REJECTED";
      expect(await response.json()).toMatchObject({ status });
      await expect(card).toHaveCount(0);
      const notice = decision === "approve" ? "실행이 끝났어요" : "요청을 거절했어요";
      await expect(page.getByText(notice, { exact: false })).toBeVisible();
      expect(requests).toHaveLength(1);
      expect(await actions()).toEqual(
        expect.arrayContaining([expect.objectContaining({ actionId: pending!.actionId, status })]),
      );
      await page.screenshot({ path: testInfo.outputPath("decision.png") });
    } finally {
      await restorePrimaries();
      if (agentCode) {
        const deleted = await page.request.delete(`/api/agents/${agentCode}`);
        expect(deleted.status()).toBe(204);
      }
      await disconnectDemoConnector(isolatedMember.email);
    }
  });
}
