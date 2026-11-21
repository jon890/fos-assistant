/**
 * 에이전트가 turn 안에 대화 폴더에 만든 HTML 이 답에 묶이고, 그 파일이 스크립트가 돌지 않는 머리글과 함께 오는지 본다.
 *
 * <p>화면이 쓰는 스트림 경로로 보낸다. 대역은 입력 맨 앞의 결과물 폴더 단락에 적힌 폴더에 `초안/index.html` 과
 * `초안/photo.png` 를 쓴다.
 */
import { call, expect, expectStatus, step, type Context, type Scenario } from "../harness.ts";
import { readEventStream } from "../../../web/src/lib/stream.ts";
import { ARTIFACT_PROBE, ARTIFACT_WRITE_PROBE, IMAGE_GENERATE_PROBE, IMAGE_GENERATE_PROMPT, IMAGE_GENERATE_TITLE } from "../fake-hermes.ts";
import { ONE_PIXEL_PNG } from "../fake-hermes/scenario-fixtures.ts";
import { DAD_BINDING } from "./binding.ts";
import { holdTurn } from "./chat-queue.ts";
import { within } from "../delegation-support.ts";

type ChatEvent = { type: string; conversationId?: string; messageId?: number; detail?: string };
type Message = {
  id: number;
  role: "USER" | "ASSISTANT";
  artifacts: { path: string; byteSize: number; deleted: boolean }[];
};

type ImageMessage = {
  id: number;
  content: string;
  artifacts: { path: string; kind: "IMAGE" | "HTML"; prompt: string | null }[];
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

/** 대화 단위 SSE 를 열어 받은 사건을 모은다. 응답 머리를 받으면 구독이 걸린 것이다. */
async function openConversationEvents(
  context: Context,
  conversationId: string,
): Promise<{ events: ChatEvent[]; close: () => Promise<void> }> {
  const controller = new AbortController();
  const response = await fetch(`${context.api}/chat/conversations/${conversationId}/events`, {
    headers: { Authorization: `Bearer ${context.tokens.dad}` },
    signal: controller.signal,
  });
  expect(response.status === 200, `대화 단위 SSE 를 열지 못했다: ${response.status}`);
  const events: ChatEvent[] = [];
  const reading = readEventStream<ChatEvent>(response, (event) => {
    events.push(event);
  }).catch(() => undefined);
  return {
    events,
    async close() {
      controller.abort();
      await reading;
    },
  };
}

async function awaitEvent(events: ChatEvent[], matches: (event: ChatEvent) => boolean, what: string): Promise<void> {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (events.some(matches)) return;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  expect(false, `${what}: 10초 안에 대화 단위 SSE 로 오지 않았다. 받은 사건: ${JSON.stringify(events)}`);
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
    expect(input.includes("artifact_write 도구가 없고 파일 도구가 있으면 위 폴더에 결과물 파일을 직접 쓴다."),
      "파일 도구만 있는 에이전트에게 직접 저장할 폴더를 안내하지 않는다");
    expect(input.includes("[스킬 관리]\n"), `Hermes 입력에 스킬 관리 단락이 없다: ${input}`);

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
        body: { profileName: DAD_BINDING.profileName, label: "artifact-e2e" },
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
      expect((context.hermes.lastSubmittedInput() ?? "").includes("artifact_write 도구가 있으면 그것으로 저장한다."),
        "MCP 도구가 있는 에이전트에게 저장 방법을 안내하지 않는다");
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

      step("profile 플러그인처럼 올린 그림이 답에 묶이고 지시문이 응답에 들며 답 글의 Hermes 쪽 경로는 빠진다");
      const imageResponse = expectStatus(
        await call(context, "/chat/messages/stream", {
          method: "POST",
          token: context.tokens.dad,
          body: { text: IMAGE_GENERATE_PROBE, agentCode: "dad" },
        }),
        200,
        "그림 만들기 스트림",
      );
      const imageEvents: ChatEvent[] = [];
      await readEventStream<ChatEvent>(
        new globalThis.Response(imageResponse.body, { headers: { "Content-Type": "text/event-stream" } }),
        (event) => imageEvents.push(event),
      );
      const imageDone = imageEvents.find((event) => event.type === "done");
      expect(imageDone?.conversationId !== undefined, `그림 만들기 done 사건이 없다: ${JSON.stringify(imageEvents)}`);
      const imageInput = context.hermes.lastSubmittedInput() ?? "";
      expect(imageInput.includes("지시문의 첫 줄에 「제목: <짧은 한국어 제목>」 을 적고"),
        `Hermes 입력에 그림 지시문 규칙이 없다: ${imageInput}`);
      const imageMessages = expectStatus(
        await call(context, `/chat/conversations/${imageDone!.conversationId!}/messages`, { token: context.tokens.dad }),
        200,
        "그림 대화 이력",
      ).json<ImageMessage[]>();
      const imageAnswer = imageMessages.find((message) => message.id === imageDone!.messageId);
      const made = imageAnswer?.artifacts[0];
      expect(
        imageAnswer?.artifacts.length === 1 &&
          made?.kind === "IMAGE" &&
          made.path === `generated/${IMAGE_GENERATE_TITLE}.png` &&
          made.prompt === IMAGE_GENERATE_PROMPT,
        `올린 그림이 답에 묶이지 않았다: ${JSON.stringify(imageAnswer?.artifacts)}`,
      );
      expect(IMAGE_GENERATE_PROMPT !== IMAGE_GENERATE_PROBE && IMAGE_GENERATE_PROMPT.length > IMAGE_GENERATE_PROBE.length,
        "지시문이 사용자의 부탁 글보다 자세하지 않다");
      expect(
        imageAnswer?.content === "수채화 고양이를 만들었다." && !JSON.stringify(imageMessages).includes("/cache/generated/images/"),
        `답 글에 Hermes 쪽 경로가 남았다: ${imageAnswer?.content}`,
      );
      const madeFile = await fetchFile(context, imageDone!.conversationId!, made!.path);
      expect(madeFile.status === 200 && madeFile.headers.get("content-type") === "image/png",
        `올린 그림을 받지 못했다: ${madeFile.status}`);
      expect(Buffer.from(await madeFile.arrayBuffer()).equals(ONE_PIXEL_PNG), "받은 그림이 올린 원본과 다르다");

      step("그림을 올리면 답이 끝나기 전에 대화 단위 SSE 로 그 경로가 오고, 다른 사용자는 그 경로로 열지 못한다");
      const imageConversationId = imageDone!.conversationId!;
      const livePath = `generated/${IMAGE_GENERATE_TITLE} 2.png`;
      const stream = await openConversationEvents(context, imageConversationId);
      try {
        const held = await holdTurn(context, IMAGE_GENERATE_PROBE, "dad", imageConversationId);
        let finished = false;
        void held.turn.completed.then(() => {
          finished = true;
        });
        await awaitEvent(stream.events, (event) => event.type === "image" && event.detail === livePath, "만든 그림의 image 사건");
        const image = stream.events.find((event) => event.type === "image");
        expect(image?.conversationId === imageConversationId, `image 사건의 대화가 다르다: ${JSON.stringify(image)}`);
        expect(!finished, "image 사건을 받았을 때 turn 이 이미 끝났다");
        // 대화 단위 SSE 를 늦게 열었거나 사건을 놓친 창은 이 목록으로 맞춘다.
        const unbound = expectStatus(
          await call(context, `/chat/conversations/${imageConversationId}/live-images`, { token: context.tokens.dad }),
          200,
          "묶이지 않은 그림 목록",
        ).json<string[]>();
        expect(JSON.stringify(unbound) === JSON.stringify([livePath]), `묶이지 않은 그림 목록이 다르다: ${JSON.stringify(unbound)}`);
        const strangerList = await fetch(`${context.api}/chat/conversations/${imageConversationId}/live-images`, {
          headers: { Authorization: `Bearer ${context.tokens.kid}` },
        });
        expect(strangerList.status === 404, `다른 사용자가 묶이지 않은 그림 목록을 읽었다: ${strangerList.status}`);
        const liveFile = await fetchFile(context, imageConversationId, livePath);
        expect(liveFile.status === 200, `답이 끝나기 전에 그림을 받지 못했다: ${liveFile.status}`);
        const encoded = livePath.split("/").map(encodeURIComponent).join("/");
        const stranger = await fetch(`${context.api}/chat/conversations/${imageConversationId}/files/${encoded}`, {
          headers: { Authorization: `Bearer ${context.tokens.kid}` },
        });
        expect(stranger.status === 404, `다른 사용자가 만든 그림을 열었다: ${stranger.status}`);
        const strangerEvents = await fetch(`${context.api}/chat/conversations/${imageConversationId}/events`, {
          headers: { Authorization: `Bearer ${context.tokens.kid}` },
        });
        expect(strangerEvents.status === 404, `다른 사용자가 대화 단위 SSE 를 열었다: ${strangerEvents.status}`);
        await strangerEvents.body?.cancel();
        context.hermes.releaseHeldRun();
        const events = await within(held.turn.completed, 15_000, "그림 turn 의 스트림이 끝나지 않았다");
        const liveDone = events.find((event) => event.type === "done") as ChatEvent | undefined;
        const liveMessages = expectStatus(
          await call(context, `/chat/conversations/${imageConversationId}/messages`, { token: context.tokens.dad }),
          200,
          "바로 보인 그림의 대화 이력",
        ).json<ImageMessage[]>();
        const liveAnswer = liveMessages.find((message) => message.id === liveDone?.messageId);
        expect(liveAnswer?.artifacts.some((artifact) => artifact.path === livePath) === true,
          `바로 보인 그림이 끝난 답에 묶이지 않았다: ${JSON.stringify(liveAnswer?.artifacts)}`);
        const afterDone = expectStatus(
          await call(context, `/chat/conversations/${imageConversationId}/live-images`, { token: context.tokens.dad }),
          200,
          "끝난 뒤 묶이지 않은 그림 목록",
        ).json<string[]>();
        expect(afterDone.length === 0, `끝난 답에 묶인 그림이 목록에 남았다: ${JSON.stringify(afterDone)}`);
      } finally {
        await stream.close();
      }
    } finally {
      expectStatus(await call(context, `/admin/agent-tokens/${issued.id}`, { method: "DELETE", token: context.tokens.dad }),
        200,
        "결과물 MCP 토큰 폐기");
    }
  },
};
