import { readFile } from "node:fs/promises";
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";
import { readEventStream } from "../../../web/src/lib/stream.ts";
import type { ChatEvent } from "../../../web/src/lib/chat-event.ts";
import { applyChatEvent, emptyActivity } from "../../../web/src/components/chat/activity/activity-state.ts";
import { applyStreamText, type TurnStreamText } from "../../../web/src/components/chat/turn-stream-text.ts";

/** backend 단위 검사와 브라우저도 이 pinned wire의 합성 envelope를 쓴다. */
export async function interimWireEvents(): Promise<Record<string, unknown>[]> {
  const wire = await readFile(new URL("../../../backend/src/test/resources/hermes/message-interim-v2026.9.24.sse", import.meta.url), "utf8");
  return wire.split("\n").filter((line) => line.startsWith("data: "))
    .map((line) => JSON.parse(line.slice(6)) as Record<string, unknown>)
    .filter((event) => event.event !== "run.completed");
}

export const interimScenario: Scenario = {
  name: "중간 안내 wire 전달과 저장",
  async run(context) {
    step("pinned Hermes envelope를 HTTP 파서와 채팅 SSE로 왕복한다");
    const input = "중간 안내 wire 검사";
    const configured = await fetch(context.hermes.baseUrl + "/__test/script", {
      method: "POST",
      body: JSON.stringify({ input, output: "확인한 최종 답이에요.", events: await interimWireEvents() }),
    });
    expect(configured.ok, "wire script 등록 실패");
    const response = expectStatus(await call(context, "/chat/messages/stream", {
      method: "POST", token: context.tokens.dad, body: { text: input, agentCode: "dad" },
    }), 200, "중간 안내 스트림");
    const received: ChatEvent[] = [];
    let activity = emptyActivity(0);
    const text: TurnStreamText = { text: "", interimKeys: new Set() };
    await readEventStream<ChatEvent>(new Response(response.body), (event) => {
      received.push(event);
      activity = applyChatEvent(activity, event);
      applyStreamText(text, event);
      if (event.type === "interim") {
        expect(!text.text.includes("일부만 "), "중간 안내 접두사가 답에 남음");
      }
    });
    const interims = received.filter((event) => event.type === "interim");
    expect(interims.length === 4, "중간 안내가 누락됨");
    expect(interims[2].streamedPrefixLength === "두 번째  안내".length, "전체 delta 재분류 길이가 다름");
    expect(interims[3].streamedPrefixLength === "일부만 ".length, "일부 접두사 재분류 길이가 다름");
    expect(text.text === "확인한 최종 답이에요.", "최종 답 조각에 안내가 섞임");
    expect(activity.items.every((item) => item.kind !== "interim"), "종료 뒤 안내가 남음");
    expect(!response.body.includes("SYNTHETIC_HIDDEN_REASONING"), "숨긴 reasoning 노출");
    const done = received.at(-1)!;
    expect(done.type === "done", "최종 완료 사건이 없음");
    const messages = expectStatus(await call(context, `/chat/conversations/${done.conversationId}/messages`, {
      token: context.tokens.dad,
    }), 200, "답 조회").json<{ role: string; content: string }[]>();
    expect(messages.filter((message) => message.role === "ASSISTANT").every((message) => message.content === text.text), "DB에 안내가 저장됨");
    const tree = expectStatus(await call(context, `/usage/executions/${done.executionId}/tree`, {
      token: context.tokens.dad,
    }), 200, "실행 사건 조회").json<{ root: { events: { eventType: string; detail: string | null }[] } }>();
    expect(tree.root.events.length === 4, "중간 안내가 실행 사건을 소비함");
    expect(!JSON.stringify(tree).includes("일부만 흘린"), "실행 기록에 안내가 저장됨");
  },
};
