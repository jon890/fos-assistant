/** 실행을 멈추면 Hermes, 스트림, 저장된 대화가 함께 취소 상태로 끝나는지 본다. */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";
import { readEventStream } from "../../../web/src/lib/stream.ts";

type ChatEvent = {
  type: "delta" | "started" | "stopped" | "error";
  text?: string;
  conversationId?: number;
  messageId?: number | null;
  executionId?: number;
};

type Execution = { id: number; conversationId: number; status: string };
type Message = { id: number; role: "USER" | "ASSISTANT"; content: string; executionId: number | null };

type Stream = {
  readonly received: ChatEvent[];
  readonly started: Promise<ChatEvent>;
  readonly firstDelta: Promise<ChatEvent>;
  readonly completed: Promise<void>;
};

function within<T>(promise: Promise<T>, milliseconds: number, message: string): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  return Promise.race([
    promise,
    new Promise<T>((_, reject) => {
      timer = setTimeout(() => reject(new Error(message)), milliseconds);
    }),
  ]).finally(() => {
    if (timer !== undefined) clearTimeout(timer);
  });
}

async function stream(context: Parameters<Scenario["run"]>[0], text: string): Promise<Stream> {
  const response = await fetch(`${context.api}/chat/messages/stream`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${context.tokens.dad}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ text, agentCode: "dad" }),
  });
  expect(response.status === 200, `중지할 스트림을 열지 못했다: ${response.status}`);

  const received: ChatEvent[] = [];
  let resolveStarted: (event: ChatEvent) => void;
  let rejectStarted: (error: Error) => void;
  const started = new Promise<ChatEvent>((resolve, reject) => {
    resolveStarted = resolve;
    rejectStarted = reject;
  });
  let resolveDelta: (event: ChatEvent) => void;
  let rejectDelta: (error: Error) => void;
  const firstDelta = new Promise<ChatEvent>((resolve, reject) => {
    resolveDelta = resolve;
    rejectDelta = reject;
  });
  const completed = readEventStream<ChatEvent>(response, (event) => {
    received.push(event);
    if (event.type === "started") resolveStarted!(event);
    if (event.type === "delta") resolveDelta!(event);
  }).catch((error: unknown) => {
    rejectStarted!(error instanceof Error ? error : new Error(String(error)));
    rejectDelta!(error instanceof Error ? error : new Error(String(error)));
    throw error;
  });
  return { received, started, firstDelta, completed };
}

async function stop(context: Parameters<Scenario["run"]>[0], executionId: number): Promise<void> {
  const stopped = expectStatus(
    await call(context, `/chat/executions/${executionId}/stop`, {
      method: "POST",
      token: context.tokens.dad,
    }),
    202,
    "실행 중지",
  ).json<{ status: string }>();
  expect(stopped.status === "stopping", `중지 응답이 다르다: ${stopped.status}`);
}

export const stopScenario: Scenario = {
  name: "실행 중지",

  async run(context) {
    step("붙잡은 실행을 멈추면 stopped 와 CANCELLED 를 남긴다");
    context.hermes.holdNextRun();
    const held = await stream(context, "중지 실행 검사");
    await within(context.hermes.waitForHeldRun(), 5_000, "가짜 Hermes 가 실행을 받지 않았다");
    const started = await within(held.started, 5_000, "started 사건을 받지 못했다");
    expect(started.executionId !== undefined, "중지할 실행 번호가 없다");

    const stolen = expectStatus(
      await call(context, `/chat/executions/${started.executionId}/stop`, {
        method: "POST",
        token: context.tokens.kid,
      }),
      404,
      "남의 실행 중지",
    ).json<{ code: string }>();
    expect(stolen.code === "EXECUTION_NOT_FOUND", `남의 실행 중지 오류가 다르다: ${stolen.code}`);

    const stoppedBefore = context.hermes.stoppedRuns().length;
    await stop(context, started.executionId!);
    await within(held.completed, 5_000, "중지 뒤 스트림이 끝나지 않았다");
    const stopped = held.received.at(-1);
    expect(stopped?.type === "stopped", `마지막 사건이 stopped 가 아니다: ${JSON.stringify(stopped)}`);
    expect(stopped.executionId === started.executionId, "중지 사건의 실행 번호가 다르다");
    expect(context.hermes.stoppedRuns().length === stoppedBefore + 1, "가짜 Hermes 가 중지 요청을 받지 않았다");

    const executions = expectStatus(
      await call(context, "/usage/executions?limit=20", { token: context.tokens.dad }),
      200,
      "중지 실행 기록 조회",
    ).json<Execution[]>();
    expect(
      executions.some((execution) => execution.id === started.executionId && execution.status === "CANCELLED"),
      "중지한 실행이 CANCELLED 로 남지 않았다",
    );

    step("Hermes 가 스트림을 닫지 않아도 유예 시간 뒤 stopped 를 보낸다");
    context.hermes.holdNextRun();
    const persistent = await stream(context, "중지 스트림 유지 검사");
    await within(context.hermes.waitForHeldRun(), 5_000, "유지할 실행이 제출되지 않았다");
    const persistentStarted = await within(persistent.started, 5_000, "유지할 실행의 started 를 받지 못했다");
    await stop(context, persistentStarted.executionId!);
    await within(persistent.completed, 12_000, "유지된 Hermes 스트림이 유예 시간 뒤에도 끝나지 않았다");
    expect(persistent.received.at(-1)?.type === "stopped", "유지된 스트림의 마지막 사건이 stopped 가 아니다");

    step("Hermes 가 빈 최종 답을 주면 첫 스트림 조각을 저장한다");
    context.hermes.holdNextRun();
    const empty = await stream(context, "중지 빈 답 검사");
    await within(context.hermes.waitForHeldRun(), 5_000, "빈 답 검사 실행이 제출되지 않았다");
    const emptyStarted = await within(empty.started, 5_000, "빈 답 검사 started 를 받지 못했다");
    await within(empty.firstDelta, 5_000, "빈 답 검사의 첫 조각을 받지 못했다");
    await stop(context, emptyStarted.executionId!);
    await within(empty.completed, 5_000, "빈 답 검사 스트림이 끝나지 않았다");
    const emptyStopped = empty.received.at(-1);
    expect(emptyStopped?.type === "stopped", "빈 답 검사의 마지막 사건이 stopped 가 아니다");
    expect(emptyStopped.conversationId !== undefined, "빈 답 검사 대화 번호가 없다");
    const messages = expectStatus(
      await call(context, `/chat/conversations/${emptyStopped.conversationId}/messages`, {
        token: context.tokens.dad,
      }),
      200,
      "중지한 빈 답 대화 이력 조회",
    ).json<Message[]>();
    const answer = messages.find((message) => message.id === emptyStopped.messageId);
    expect(answer?.content === "화면에서만 ", `첫 스트림 조각이 남지 않았다: ${answer?.content}`);
    expect(answer.executionId === emptyStopped.executionId, "저장한 답과 중지 실행이 연결되지 않았다");
  },
};
