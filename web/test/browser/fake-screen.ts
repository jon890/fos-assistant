import type { Page } from "../../node_modules/@playwright/test/index.js";

/** 4x6 JPEG 한 장이다. 기본 프레임(400x600)과 비율이 같다. */
export const FRAME =
  "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAA0JCgsKCA0LCgsODg0PEyAVExISEyccHhcgLikxMC4pLSwzOko+MzZGNywtQFdBRkxOUlNSMj5aYVpQYEpRUk//2wBDAQ4ODhMREyYVFSZPNS01T09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT0//wAARCAAGAAQDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDrqKKK2Ef/2Q==";


export type Input = Record<string, unknown> & { type: string };
export type Reply = { status: number; body?: unknown };

/** 화면 안의 가짜 SSE 다. 시험이 사건을 밀어 넣고 연결을 끝낸다. */
type FakeScreen = {
  opens: string[];
  fail: { status: number; body: string } | null;
  push(text: string): void;
  end(): void;
};

type FakeWindow = { __screen: FakeScreen };

export function sse(events: [string, unknown][]): string {
  return events
    .map(([name, data]) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`)
    .join("");
}

/**
 * `page.route` 는 응답을 한 번에 보내고 닫으므로 SSE 는 화면의 `fetch` 를 바꿔 흉내 낸다.
 * 입력 POST 는 `page.route` 로 받아 본문을 모은다.
 */
export async function fakeScreen(
  page: Page,
  onInput?: (input: Input) => Promise<void>,
) {
  const inputs: Input[] = [];
  const state: { reply: Reply } = { reply: { status: 204 } };
  await page.addInitScript(() => {
    const encoder = new TextEncoder();
    let current: ReadableStreamDefaultController<Uint8Array> | null = null;
    const screen: FakeScreen = {
      opens: [],
      fail: null,
      push: (text) => current?.enqueue(encoder.encode(text)),
      end: () => {
        current?.close();
        current = null;
      },
    };
    (window as unknown as FakeWindow).__screen = screen;
    const original = window.fetch.bind(window);
    window.fetch = (input, init) => {
      const url = new URL(
        input instanceof Request ? input.url : String(input),
        location.href,
      );
      if (url.pathname !== "/api/browser/screen") return original(input, init);
      screen.opens.push(url.href);
      if (screen.fail) {
        const { status, body } = screen.fail;
        screen.fail = null;
        return Promise.resolve(
          new Response(body, {
            status,
            headers: { "Content-Type": "application/json" },
          }),
        );
      }
      const body = new ReadableStream<Uint8Array>({
        start(controller) {
          current = controller;
          init?.signal?.addEventListener("abort", () => {
            if (current === controller) current = null;
            controller.error(new DOMException("aborted", "AbortError"));
          });
        },
      });
      return Promise.resolve(
        new Response(body, {
          headers: { "Content-Type": "text/event-stream" },
        }),
      );
    };
  });
  await page.route("**/api/browser", (route) =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        enabled: true,
        exists: true,
        status: "RUNNING",
        lastError: null,
        startedAt: null,
        lastActiveAt: null,
        idleTimeoutSeconds: 600,
      }),
    }),
  );
  await page.route("**/api/browser/screen/input", async (route) => {
    const input = route.request().postDataJSON() as Input;
    inputs.push(input);
    await onInput?.(input);
    const { status, body } = state.reply;
    return route.fulfill(
      body === undefined
        ? { status }
        : {
            status,
            contentType: "application/json",
            body: JSON.stringify(body),
          },
    );
  });
  return {
    inputs,
    state,
    opens: () =>
      page.evaluate(() => (window as unknown as FakeWindow).__screen.opens),
    push: (events: [string, unknown][]) =>
      page.evaluate(
        (text) => (window as unknown as FakeWindow).__screen.push(text),
        sse(events),
      ),
    end: () =>
      page.evaluate(() => (window as unknown as FakeWindow).__screen.end()),
    fail: (status: number, body: unknown) =>
      page.evaluate(
        (fail) => {
          (window as unknown as FakeWindow).__screen.fail = fail;
        },
        { status, body: JSON.stringify(body) },
      ),
  };
}
