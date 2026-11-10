import {
  conversationIdOf,
  expect,
  setAgentVisibility,
  setSession,
  test,
} from "./fixtures.ts";
import type { Page } from "../../web/node_modules/@playwright/test/index.js";

type LossMode = "before" | "accepted" | "headers" | "started";
type Probe = {
  stream: number;
  fallback: number;
  acceptedId: string | null;
  executionId: number | null;
};

/** 송신 훅에만 손실을 전달한다. 실제 응답은 끝까지 읽어 원 실행을 유지한다. */
async function injectLoss(page: Page, mode: LossMode, status?: number) {
  await page.evaluate(
    ({ mode, status }) => {
      const httpBody =
        status === 502
          ? JSON.stringify({
              code: "INTERNAL_ERROR",
              message: "시험이 전달한 서버 오류예요.",
            })
          : "{}";
      const probe: Probe = {
        stream: 0,
        fallback: 0,
        acceptedId: null,
        executionId: null,
      };
      Object.assign(window, { sendProbe: probe });
      const original = window.fetch.bind(window);
      window.fetch = async (input, init) => {
        const url =
          typeof input === "string"
            ? input
            : input instanceof URL
              ? input.href
              : input.url;
        const pathname = new URL(url, location.href).pathname;
        if (pathname === "/api/chat" && init?.method === "POST")
          probe.fallback++;
        if (pathname !== "/api/chat/stream" || init?.method !== "POST")
          return original(input, init);
        probe.stream++;
        if (mode === "before") {
          if (status) return new Response(httpBody, { status });
          throw new TypeError("시험의 수락 전 연결 실패");
        }
        const response = await original(input, init);
        const reader = response.body!.getReader();
        const decoder = new TextDecoder();
        let raw = "";
        while (!raw.includes('"type":"started"') || !raw.includes("\n\n")) {
          const part = await reader.read();
          if (part.done) throw new Error("started 사건이 없다");
          raw += decoder.decode(part.value, { stream: true });
        }
        const started = raw
          .split("\n")
          .filter((line) => line.startsWith("data:"))
          .map((line) => JSON.parse(line.slice(5)))
          .find((event) => event.type === "started");
        probe.acceptedId = started.conversationId;
        probe.executionId = started.executionId;
        void (async () => {
          while (!(await reader.read()).done) {
            /* 원 응답을 소비한다. */
          }
        })();
        if (status) return new Response(httpBody, { status });
        if (mode === "accepted")
          throw new TypeError("시험의 수락 후 응답 유실");
        let emitted = false;
        const body = new ReadableStream<Uint8Array>({
          pull(controller) {
            if (mode === "started" && !emitted) {
              emitted = true;
              controller.enqueue(new TextEncoder().encode(raw));
            } else controller.error(new TypeError("시험의 본문 유실"));
          },
        });
        return new Response(body, {
          status: response.status,
          headers: response.headers,
        });
      };
    },
    { mode, status },
  );
}

async function probe(page: Page): Promise<Probe> {
  return page.evaluate(
    () => (window as unknown as { sendProbe: Probe }).sendProbe,
  );
}

/** 저장된 메시지 번호와 실행 번호를 센다. 본문이나 시각으로 대화를 추측하지 않는다. */
async function stored(page: Page, id: string) {
  const response = await page.request.get(
    `/api/chat/conversations/${id}/messages`,
  );
  expect(response.ok()).toBeTruthy();
  const messages = (await response.json()) as Array<{
    id: number;
    role: string;
    executionId: number | null;
  }>;
  const pending = await page.request.get(
    `/api/chat/conversations/${id}/pending`,
  );
  expect(pending.ok()).toBeTruthy();
  return {
    questions: messages
      .filter((message) => message.role === "USER")
      .map((message) => message.id),
    executions: [
      ...new Set(
        messages
          .map((message) => message.executionId)
          .filter((id) => id !== null),
      ),
    ],
    answers: messages.filter((message) => message.role === "ASSISTANT").length,
    pending: (await pending.json()).items.length,
  };
}

test.beforeAll(async () => {
  await setAgentVisibility("browser", "GROUP", null);
});
test.afterAll(async () => {
  await setAgentVisibility("browser", "PRIVATE", "browser@example.com");
});

for (const existing of [false, true]) {
  for (const mode of ["before", "accepted", "headers", "started"] as const) {
    test(`${existing ? "기존" : "새"} 대화의 ${mode} 손실은 자동 재전송 없이 상태를 복구한다`, async ({
      page,
      context,
      isolatedMember,
      hermes,
    }) => {
      await setSession(context, isolatedMember);
      let id: string | null = null;
      if (existing) {
        const created = await page.request.post("/api/chat/conversations", {
          data: { agentCode: "browser" },
        });
        expect(created.ok()).toBeTruthy();
        id = (await created.json()).conversationId;
      }
      await page.goto(id ? `/chat/${id}` : "/");
      await injectLoss(page, mode);
      if (mode !== "before") await hermes.holdNextRun();
      const text = `연결 손실 ${existing}-${mode}`;
      await page.getByRole("textbox", { name: "메시지" }).fill(text);
      await page
        .getByTestId("composer-shell")
        .getByRole("button", { name: "보내기" })
        .click();
      try {
        await expect.poll(async () => (await probe(page)).stream).toBe(1);
        if (mode === "before") {
          await expect(
            page.getByRole("textbox", { name: "메시지" }),
          ).toHaveValue(text);
          await expect(page.getByText(/응답 연결이 끊겼어요/)).toBeVisible();
          expect((await probe(page)).fallback).toBe(0);
          id ??= conversationIdOf(page.url());
          expect(await stored(page, id)).toEqual({
            questions: [],
            executions: [],
            answers: 0,
            pending: 0,
          });
          if (!existing) {
            // started를 받지 못해도 실제로 확보한 빈 대화는 목록에 남아야 한다.
            const openSidebar = page.getByRole("button", {
              name: "사이드바 열기",
            });
            if (await openSidebar.isVisible()) await openSidebar.click();
            await expect(
              page
                .getByRole("navigation", { name: "대화 목록" })
                .locator(`a[href="/chat/${id}"]`),
            ).toBeVisible();
          }
          return;
        }
        await hermes.waitForHeldRun();
        await expect
          .poll(async () => (await probe(page)).acceptedId)
          .not.toBeNull();
        const accepted = await probe(page);
        id = accepted.acceptedId!;
        // 기존 코드에서는 별도 POST가 질문이나 대기 행을 하나 더 만든다.
        const held = await stored(page, id);
        console.log("SEND_LOSS", {
          existing,
          mode,
          probe: await probe(page),
          held,
        });
        expect((await probe(page)).fallback).toBe(0);
        await expect(
          page
            .getByTestId("composer-shell")
            .getByRole("button", { name: "중지" }),
        ).toBeEnabled();
        expect(conversationIdOf(page.url())).toBe(id);
        expect(held.questions).toHaveLength(1);
        expect(held.pending).toBe(0);
        const running = await page.request.get(
          `/api/chat/conversations/${id}/running`,
        );
        expect(await running.json()).toMatchObject({
          running: true,
          executionId: accepted.executionId,
        });
        await page.getByRole("textbox", { name: "메시지" }).fill("이어 쓸 글");
        await hermes.releaseHeldRun();
        await expect(page.getByTestId("assistant-message")).toHaveCount(1);
        await expect(
          page
            .getByTestId("composer-shell")
            .getByRole("button", { name: "중지" }),
        ).toHaveCount(0);
        expect(await stored(page, id)).toEqual({
          questions: held.questions,
          executions: [accepted.executionId],
          answers: 1,
          pending: 0,
        });
        expect(await probe(page)).toMatchObject({ stream: 1, fallback: 0 });
        await expect(page.getByRole("textbox", { name: "메시지" })).toHaveValue(
          "이어 쓸 글",
        );
        await expect(page.getByTestId("user-message")).toHaveCount(1);
      } finally {
        await hermes.releaseHeldRun();
        if (mode !== "before") {
          const response = await page.request.get("/api/chat/conversations");
          expect(response.ok()).toBeTruthy();
          const listing = (await response.json()) as {
            items: Array<{ id: string }>;
          };
          await expect
            .poll(async () => {
              const running = await Promise.all(
                listing.items.map(async ({ id }) => {
                  const response = await page.request.get(
                    `/api/chat/conversations/${id}/running`,
                  );
                  expect(response.ok()).toBeTruthy();
                  return (await response.json()).running;
                }),
              );
              return running.some(Boolean);
            })
            .toBe(false);
          const all = await Promise.all(
            listing.items.map(({ id }) => stored(page, id)),
          );
          console.log("SEND_LOSS_FINAL", {
            existing,
            mode,
            probe: await probe(page),
            conversations: all.length,
            questions: all.flatMap((item) => item.questions).length,
            executions: all.flatMap((item) => item.executions).length,
            pending: all.reduce((sum, item) => sum + item.pending, 0),
          });
          expect(all).toHaveLength(1);
          expect(all[0]?.questions).toHaveLength(1);
          expect(all[0]?.executions).toHaveLength(1);
        }
      }
    });
  }
}

test("같은 화면 사건에서 두 번 보내도 빈 대화와 송신을 한 번만 만든다", async ({
  page,
  context,
  isolatedMember,
}) => {
  await setSession(context, isolatedMember);
  await page.goto("/");
  const counts = { creates: 0, stream: 0, fallback: 0 };
  let requestedId: string | null = null;
  page.on("request", (request) => {
    if (request.method() !== "POST") return;
    const pathname = new URL(request.url()).pathname;
    if (pathname === "/api/chat/conversations") counts.creates++;
    if (pathname === "/api/chat") counts.fallback++;
    if (pathname === "/api/chat/stream") {
      counts.stream++;
      requestedId = request.postDataJSON().conversationId;
    }
  });
  await page.getByRole("textbox", { name: "메시지" }).fill("한 번 보내는 질문");
  await page.getByTestId("composer-shell").evaluate((shell) => {
    const form = shell.closest("form")!;
    form.requestSubmit();
    form.requestSubmit();
  });
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
  expect(counts).toEqual({ creates: 1, stream: 1, fallback: 0 });
  expect(requestedId).toBe(conversationIdOf(page.url()));
  const result = await stored(page, requestedId!);
  expect(result.questions).toHaveLength(1);
  expect(result.executions).toHaveLength(1);
  expect(result.pending).toBe(0);
});

for (const status of [404, 405, 415, 501, 502]) {
  for (const accepted of [false, true]) {
    test(`HTTP ${status}의 ${accepted ? "수락 뒤" : "수락 전"} 실패도 별도 POST를 보내지 않는다`, async ({
      page,
      context,
      isolatedMember,
      hermes,
    }) => {
      await setSession(context, isolatedMember);
      await page.goto("/");
      await injectLoss(page, accepted ? "accepted" : "before", status);
      if (accepted) await hermes.holdNextRun();
      await page
        .getByRole("textbox", { name: "메시지" })
        .fill("HTTP 상태만으로 재전송하지 않아요");
      await page
        .getByTestId("composer-shell")
        .getByRole("button", { name: "보내기" })
        .click();
      try {
        if (accepted) {
          await hermes.waitForHeldRun();
          await expect(
            page
              .getByTestId("composer-shell")
              .getByRole("button", { name: "중지" }),
          ).toBeEnabled();
          await hermes.releaseHeldRun();
          await expect(page.getByTestId("assistant-message")).toHaveCount(1);
        } else {
          await expect(
            page.getByRole("textbox", { name: "메시지" }),
          ).toHaveValue("HTTP 상태만으로 재전송하지 않아요");
          await expect(
            page.getByText(
              status === 502
                ? "시험이 전달한 서버 오류예요."
                : /응답 연결이 끊겼어요/,
            ),
          ).toBeVisible();
        }
        expect(await probe(page)).toMatchObject({ stream: 1, fallback: 0 });
        const result = await stored(page, conversationIdOf(page.url()));
        expect(result.questions).toHaveLength(accepted ? 1 : 0);
        expect(result.executions).toHaveLength(accepted ? 1 : 0);
        expect(result.pending).toBe(0);
      } finally {
        await hermes.releaseHeldRun();
      }
    });
  }
}

for (const lostAfterCreation of [false, true]) {
  test(`빈 대화 생성의 ${lostAfterCreation ? "수락 뒤" : "수락 전"} 실패에서는 실행을 시작하지 않는다`, async ({
    page,
    context,
    isolatedMember,
  }) => {
    await setSession(context, isolatedMember);
    await page.goto("/");
    await page.evaluate((lostAfterCreation) => {
      Object.assign(window, { createProbe: { creates: 0, sends: 0 } });
      const original = window.fetch.bind(window);
      window.fetch = async (input, init) => {
        const url =
          typeof input === "string"
            ? input
            : input instanceof URL
              ? input.href
              : input.url;
        const pathname = new URL(url, location.href).pathname;
        const probe = (
          window as unknown as {
            createProbe: { creates: number; sends: number };
          }
        ).createProbe;
        if (
          init?.method === "POST" &&
          ["/api/chat", "/api/chat/stream"].includes(pathname)
        )
          probe.sends++;
        if (pathname !== "/api/chat/conversations" || init?.method !== "POST")
          return original(input, init);
        probe.creates++;
        if (lostAfterCreation) await original(input, init);
        throw new TypeError("시험의 대화 생성 응답 유실");
      };
    }, lostAfterCreation);
    await page
      .getByRole("textbox", { name: "메시지" })
      .fill("보내지 않은 질문");
    await page
      .getByTestId("composer-shell")
      .getByRole("button", { name: "보내기" })
      .click();
    await expect(page.getByText(/대화를 시작하지 못했어요/)).toBeVisible();
    await expect(page.getByRole("textbox", { name: "메시지" })).toHaveValue(
      "보내지 않은 질문",
    );
    expect(
      await page.evaluate(
        () => (window as unknown as { createProbe: unknown }).createProbe,
      ),
    ).toEqual({ creates: 1, sends: 0 });
    const response = await page.request.get("/api/chat/conversations");
    const listing = (await response.json()) as { items: Array<{ id: string }> };
    expect(listing.items).toHaveLength(lostAfterCreation ? 1 : 0);
    for (const { id } of listing.items)
      expect(await stored(page, id)).toEqual({
        questions: [],
        executions: [],
        answers: 0,
        pending: 0,
      });
  });
}

for (const boundary of ["creation", "recovery"] as const) {
  test(`지연 ${boundary} 실패는 대기 중 새로 쓴 글을 보존한다`, async ({
    page,
    context,
    isolatedMember,
  }) => {
    await setSession(context, isolatedMember);
    await page.goto("/");
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    let reached = false;
    let creates = 0;
    let streams = 0;
    let fallback = 0;
    page.on("request", (request) => {
      if (request.method() !== "POST") return;
      const pathname = new URL(request.url()).pathname;
      if (pathname === "/api/chat/conversations") creates++;
      if (pathname === "/api/chat/stream") streams++;
      if (pathname === "/api/chat") fallback++;
    });
    if (boundary === "creation") {
      await page.route("**/api/chat/conversations", async (route) => {
        if (route.request().method() !== "POST") return route.continue();
        reached = true;
        await gate;
        await route.abort("failed");
      });
    } else {
      await page.route("**/api/chat/stream", (route) => route.abort("failed"));
      await page.route("**/api/chat/conversations/*/running", async (route) => {
        reached = true;
        await gate;
        // 지연만 주입하고 running 판정은 실제 BFF와 Backend에 맡긴다.
        await route.continue();
      });
    }
    const input = page.getByRole("textbox", { name: "메시지" });
    await input.fill("처음 보낸 질문");
    await page
      .getByTestId("composer-shell")
      .getByRole("button", { name: "보내기" })
      .click();
    try {
      await expect.poll(() => reached).toBe(true);
      await expect(input).toHaveValue("");
      await input.fill("대기 중 새로 쓴 글");
    } finally {
      release();
    }
    await expect(
      page.getByText(
        boundary === "creation"
          ? /대화를 시작하지 못했어요/
          : /응답 연결이 끊겼어요/,
      ),
    ).toBeVisible();
    await expect(input).toHaveValue("대기 중 새로 쓴 글");
    expect(creates).toBe(1);
    expect(streams).toBe(boundary === "creation" ? 0 : 1);
    expect(fallback).toBe(0);
    await expect(page.getByTestId("user-message")).toHaveCount(0);
    const response = await page.request.get("/api/chat/conversations");
    expect(response.ok()).toBeTruthy();
    const listing = (await response.json()) as { items: Array<{ id: string }> };
    expect(listing.items).toHaveLength(boundary === "creation" ? 0 : 1);
    for (const { id } of listing.items) {
      expect(await stored(page, id)).toEqual({
        questions: [],
        executions: [],
        answers: 0,
        pending: 0,
      });
      const running = await page.request.get(
        `/api/chat/conversations/${id}/running`,
      );
      expect(running.ok()).toBeTruthy();
      expect((await running.json()).running).toBe(false);
    }
  });
}
