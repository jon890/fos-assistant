/**
 * Hermes v0.23.2 모양에서 도는 turn 중에 Hermes 가 다시 뜨면, Control Plane 이 잃은 실행을 해제하고 그 turn 을 실패로 적으며
 * 같은 대화의 다음 turn 이 막히지 않는지 본다(docs/features/hermes-upgrade/spec.md 의 REQ2).
 *
 * <p>가짜 Hermes 는 잃은 실행이 해제되기 전까지 같은 session 의 뒤 실행을 `queued` 로 둔다. 해제가 없으면 다음 turn 이 끝나지 않는다.
 */
import { expect, fail, step, type Scenario } from "../harness.ts";
import { openStream, within, type ChatEvent } from "../delegation-support.ts";
import { awaitMessages, holdTurn } from "./chat-queue.ts";

const TURN_TIMEOUT_MS = 15_000;

async function awaitResolved(read: () => readonly string[], timeoutMs: number): Promise<readonly string[]> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const resolved = read();
    if (resolved.length > 0) return resolved;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  return fail(`${timeoutMs / 1000}초 안에 잃은 실행의 해제 요청이 오지 않았다`);
}

export const hermesV023RestartScenario: Scenario = {
  name: "Hermes v0.23 재기동으로 잃은 실행",

  async run(context) {
    context.hermes.useHermesShape("v0.23");
    try {
      step("도는 turn 중에 Hermes 가 다시 뜨면 Control Plane 이 잃은 실행을 해제하고 그 turn 은 실패로 끝난다");
      const lost = await holdTurn(context, "재기동 검사 첫 글", "dad");
      const conversationId = lost.started.conversationId!;
      context.hermes.restartHermes();
      const events: ChatEvent[] = await within(lost.turn.completed, TURN_TIMEOUT_MS, "잃은 turn 의 스트림이 끝나지 않았다");
      const resolved = await awaitResolved(() => context.hermes.resolvedRuns(), TURN_TIMEOUT_MS);
      expect(resolved.length >= 1, `해제 요청이 없다: ${JSON.stringify(resolved)}`);
      expect(
        events.some((event) => event.type === "error"),
        `잃은 turn 이 실패로 끝나지 않았다: ${JSON.stringify(events.map((event) => event.type))}`,
      );

      step("같은 대화에 다음 글을 보내면 대기에 머물지 않고 답이 끝까지 온다");
      const next = await openStream(context, "재기동 뒤 다음 글", "dad", conversationId);
      await within(next.completed, TURN_TIMEOUT_MS, "재기동 뒤 다음 turn 의 스트림이 끝나지 않았다");
      const messages = await awaitMessages(
        context,
        conversationId,
        (list) => list.at(-1)?.role === "ASSISTANT" && list.at(-1)!.content.includes("재기동 뒤 다음 글"),
        TURN_TIMEOUT_MS,
        "재기동 뒤 다음 turn",
      );
      expect(messages.length > 0, "재기동 뒤 대화 이력이 비었다");
    } finally {
      context.hermes.useHermesShape("v0.21");
    }
  },
};
