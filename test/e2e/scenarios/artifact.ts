/**
 * 에이전트가 turn 안에 대화 폴더에 만든 HTML 이 답에 묶이고, 그 파일이 스크립트가 돌지 않는 머리글과 함께 오는지 본다.
 *
 * <p>화면이 쓰는 스트림 경로로 보낸다. 대역은 입력 맨 앞의 결과물 폴더 단락에 적힌 폴더에 `초안/index.html` 과
 * `초안/photo.png` 를 쓴다.
 */
import { call, expect, expectStatus, step, type Context, type Scenario } from "../harness.ts";
import { readEventStream } from "../../../web/src/lib/stream.ts";
import { ARTIFACT_PROBE, ARTIFACT_WRITE_PROBE } from "../fake-hermes.ts";

type ChatEvent = { type: string; conversationId?: string; messageId?: number };
type Message = {
  id: number;
  role: "USER" | "ASSISTANT";
  artifacts: { path: string; byteSize: number; deleted: boolean }[];
};

const CSP =
  "sandbox allow-same-origin allow-popups allow-popups-to-escape-sandbox; default-src 'none'; " +
  "img-src 'self' data:; style-src 'self' 'unsafe-inline'; base-uri 'none'; form-action 'none'";

/** 파일 본문 경로를 부른다. 머리글을 보아야 해서 {@link call} 대신 직접 부른다. */
async function fetchFile(context: Context, conversationId: string, path: string): Promise<globalThis.Response> {
  const encoded = path.split("/").map(encodeURIComponent).join("/");
  return fetch(`${context.api}/chat/conversations/${conversationId}/files/${encoded}`, {
    headers: { Authorization: `Bearer ${context.tokens.dad}` },
  });
}

export const artifactScenario: Scenario = {
  name: "결과물 파일",
  async run(context) {
    step("스트림으로 보낸 turn 이 대화 폴더에 만든 HTML 이 답에 묶인다");
    const response = expectStatus(
      await call(context, "/chat/messages/stream", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: ARTIFACT_PROBE, agentCode: "dad" },
      }),
      200,
      "결과물 파일 스트림",
    );
    const received: ChatEvent[] = [];
    await readEventStream<ChatEvent>(
      new globalThis.Response(response.body, { headers: { "Content-Type": "text/event-stream" } }),
      (event) => received.push(event),
    );
    const done = received.find((event) => event.type === "done");
    expect(done?.conversationId !== undefined, `done 사건이 없다: ${JSON.stringify(received)}`);
    const conversationId = done!.conversationId!;

    const input = context.hermes.lastSubmittedInput() ?? "";
    expect(input.startsWith("[결과물 폴더]\n"), `Hermes 입력이 결과물 폴더 단락으로 시작하지 않는다: ${input}`);

    const messages = expectStatus(
      await call(context, `/chat/conversations/${conversationId}/messages`, { token: context.tokens.dad }),
      200,
      "결과물 대화 이력",
    ).json<Message[]>();
    const answer = messages.find((message) => message.id === done!.messageId);
    expect(
      answer?.artifacts.length === 1 &&
        answer.artifacts[0]?.path === "초안/index.html" &&
        answer.artifacts[0].byteSize > 0 &&
        !answer.artifacts[0].deleted,
      `답에 묶인 결과물이 다르다: ${JSON.stringify(answer?.artifacts)}`,
    );
    expect(
      messages.filter((message) => message.role === "USER").every((message) => message.artifacts.length === 0),
      "사용자 메시지에 결과물이 붙었다",
    );

    step("HTML 은 스크립트를 막는 머리글과 함께 온다");
    const html = await fetchFile(context, conversationId, "초안/index.html");
    const body = await html.text();
    expect(html.status === 200, `HTML 을 받지 못했다: ${html.status} ${body}`);
    expect(body.includes("<title>초안</title>"), `HTML 본문이 다르다: ${body}`);
    expect(
      html.headers.get("content-type")?.startsWith("text/html") === true,
      `HTML 의 형식이 다르다: ${html.headers.get("content-type")}`,
    );
    expect(html.headers.get("content-security-policy") === CSP,
      `CSP 가 다르다: ${html.headers.get("content-security-policy")}`);
    expect(html.headers.get("x-content-type-options") === "nosniff", "nosniff 가 없다");
    expect(html.headers.get("cache-control") === "private, no-cache",
      `Cache-Control 이 다르다: ${html.headers.get("cache-control")}`);

    step("HTML 이 부르는 사진은 행이 없어도 같은 폴더에서 온다");
    const photo = await fetchFile(context, conversationId, "초안/photo.png");
    expect(photo.status === 200, `사진을 받지 못했다: ${photo.status}`);
    expect(photo.headers.get("content-type") === "image/png", `사진의 형식이 다르다: ${photo.headers.get("content-type")}`);
    expect(photo.headers.get("content-security-policy") === CSP, "사진에도 같은 CSP 가 붙어야 한다");

    step("남의 토큰으로는 그 파일을 받지 못한다");
    const other = await fetch(
      `${context.api}/chat/conversations/${conversationId}/files/${encodeURIComponent("초안")}/index.html`,
      { headers: { Authorization: `Bearer ${context.tokens.kid}` } },
    );
    const otherBody = await other.text();
    expect(other.status === 404 && otherBody.includes("CONVERSATION_NOT_FOUND"),
      `남의 대화 파일 요청이 거절되지 않았다: ${other.status} ${otherBody}`);

    step("MCP 도구가 같은 turn 의 HTML과 CSS를 저장하고 HTML만 답에 묶는다");
    const issued = expectStatus(
      await call(context, "/admin/agent-tokens", {
        method: "POST",
        token: context.tokens.dad,
        body: { userEmail: "dad@example.com", label: "artifact-e2e" },
      }),
      200,
      "결과물 MCP 토큰 발급",
    ).json<{ id: number; token: string }>();
    context.hermes.setArtifactWriteMcp(context.api.replace(/\/api\/v1$/, "") + "/mcp", issued.token);
    try {
      const mcpResponse = expectStatus(
        await call(context, "/chat/messages/stream", {
          method: "POST",
          token: context.tokens.dad,
          body: { text: ARTIFACT_WRITE_PROBE, agentCode: "dad" },
        }),
        200,
        "MCP 결과물 파일 스트림",
      );
      const mcpEvents: ChatEvent[] = [];
      await readEventStream<ChatEvent>(
        new globalThis.Response(mcpResponse.body, { headers: { "Content-Type": "text/event-stream" } }),
        (event) => mcpEvents.push(event),
      );
      const mcpDone = mcpEvents.find((event) => event.type === "done");
      expect(mcpDone?.conversationId !== undefined, `MCP 결과물 done 사건이 없다: ${JSON.stringify(mcpEvents)}`);
      const mcpConversationId = mcpDone!.conversationId!;
      const mcpMessages = expectStatus(
        await call(context, `/chat/conversations/${mcpConversationId}/messages`, { token: context.tokens.dad }),
        200,
        "MCP 결과물 대화 이력",
      ).json<Message[]>();
      const mcpAnswer = mcpMessages.find((message) => message.id === mcpDone!.messageId);
      expect(mcpAnswer?.artifacts.length === 1 && mcpAnswer.artifacts[0]?.path === "test/index.html",
        `MCP HTML 결과물이 답에 묶이지 않았다: ${JSON.stringify(mcpAnswer?.artifacts)}`);
      const mcpHtml = await fetchFile(context, mcpConversationId, "test/index.html");
      expect(mcpHtml.status === 200 && (await mcpHtml.text()).includes("MCP 결과물"), "MCP HTML 본문이 다르다");
      const mcpCss = await fetchFile(context, mcpConversationId, "test/style.css");
      expect(mcpCss.status === 200 && (await mcpCss.text()).includes("color: navy"), "MCP CSS 본문이 다르다");
    } finally {
      expectStatus(await call(context, `/admin/agent-tokens/${issued.id}`, { method: "DELETE", token: context.tokens.dad }),
        200,
        "결과물 MCP 토큰 폐기");
    }
  },
};
