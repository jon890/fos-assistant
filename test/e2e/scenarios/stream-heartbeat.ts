/** 사건이 없는 동안에도 스트림에 주석 줄이 흘러 앞단 프록시가 연결을 끊지 않는지 본다. */
import { expect, step, type Scenario } from "../harness.ts";

/** `run.ts` 가 backend 에 주는 heartbeat 간격이다. 기본값 20초를 기다리지 않으려고 짧게 준다. */
export const E2E_STREAM_HEARTBEAT = "500ms";

export const streamHeartbeatScenario: Scenario = {
  name: "스트림 heartbeat",

  async run(context) {
    step("붙잡은 실행이 조용한 동안 SSE 주석 줄이 온다");
    context.hermes.holdNextRun();
    const response = await fetch(`${context.api}/chat/messages/stream`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${context.tokens.dad}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ text: "heartbeat 검사", agentCode: "dad" }),
    });
    expect(response.status === 200, `스트림을 열지 못했다: ${response.status}`);
    await context.hermes.waitForHeldRun();

    const reader = response.body!.getReader();
    const decoder = new TextDecoder();
    let received = "";
    const deadline = Date.now() + 5_000;
    while (!/^:ping$/m.test(received) && Date.now() < deadline) {
      const { value, done } = await reader.read();
      if (done) break;
      received += decoder.decode(value, { stream: true });
    }
    expect(/^:ping$/m.test(received), `붙잡은 동안 주석 줄이 오지 않았다: ${JSON.stringify(received.slice(-300))}`);

    step("풀어 준 실행은 평소처럼 끝난다");
    context.hermes.releaseHeldRun();
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      received += decoder.decode(value, { stream: true });
    }
    expect(received.includes('"type":"done"'), `풀어 준 뒤 done 사건이 오지 않았다: ${JSON.stringify(received.slice(-300))}`);
  },
};
