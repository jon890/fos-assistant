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
    // 붙잡은 동안에는 heartbeat 말고 오는 바이트가 없다. 제한 시간을 read() 와 경쟁시켜야
    // heartbeat 가 깨졌을 때 read() 가 끝없이 기다리지 않고 5초 뒤 실패한다.
    const timedOut = Symbol("timed out");
    const timeout = new Promise<typeof timedOut>((resolve) => setTimeout(() => resolve(timedOut), 5_000));
    while (!/^:ping$/m.test(received)) {
      const chunk = await Promise.race([reader.read(), timeout]);
      if (chunk === timedOut || chunk.done) break;
      received += decoder.decode(chunk.value, { stream: true });
    }
    if (!/^:ping$/m.test(received)) {
      // 뒤 시나리오가 붙잡힌 실행에 막히지 않게 풀고 연결을 닫은 뒤 실패로 끝낸다.
      context.hermes.releaseHeldRun();
      await reader.cancel();
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
