import type {
  Page,
  Route,
} from "../../web/node_modules/@playwright/test/index.js";
import { expect, test } from "./fixtures.ts";

/** 4x6 JPEG 한 장이다. 그림 크기는 `frame` 사건의 `width`, `height` 가 정한다. */
const FRAME =
  "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAA0JCgsKCA0LCgsODg0PEyAVExISEyccHhcgLikxMC4pLSwzOko+MzZGNywtQFdBRkxOUlNSMj5aYVpQYEpRUk//2wBDAQ4ODhMREyYVFSZPNS01T09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT0//wAARCAAGAAQDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDrqKKK2Ef/2Q==";

type Input = Record<string, unknown> & { type: string };

function sse(events: [string, unknown][]): string {
  return events
    .map(([name, data]) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`)
    .join("");
}

function fulfillSse(route: Route, events: [string, unknown][]) {
  return route.fulfill({
    status: 200,
    headers: {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache",
    },
    body: sse(events),
  });
}

/**
 * 켜진 브라우저와 로그인 화면을 대신 답한다. 첫 SSE 는 프레임 하나와 탭 둘을 보내고 끝난다.
 * 화면이 다시 열면 `close()` 를 부를 때까지 기다렸다가 `closed` 를 보낸다.
 */
async function fakeScreen(page: Page) {
  const inputs: Input[] = [];
  const screenUrls: string[] = [];
  let close!: () => void;
  const closing = new Promise<void>((resolve) => {
    close = resolve;
  });
  await page.route("**/api/browser", (route) =>
    route.fulfill({
      status: 200,
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
  await page.route(/\/api\/browser\/screen(\?.*)?$/, async (route) => {
    screenUrls.push(route.request().url());
    if (screenUrls.length === 1) {
      return fulfillSse(route, [
        ["frame", { data: FRAME, width: 400, height: 600 }],
        [
          "tabs",
          [
            {
              id: "A1",
              title: "로그인",
              url: "https://example.com/login",
              active: true,
            },
            {
              id: "B2",
              title: "팝업",
              url: "https://example.com/popup",
              active: false,
            },
          ],
        ],
      ]);
    }
    await closing;
    return fulfillSse(route, [["closed", { reason: "replaced" }]]);
  });
  await page.route("**/api/browser/screen/input", (route) => {
    inputs.push(route.request().postDataJSON() as Input);
    return route.fulfill({ status: 204 });
  });
  return { inputs, screenUrls, close };
}

async function openScreen(page: Page, path: string) {
  await page.goto(path);
  await page.getByRole("button", { name: "화면 열기" }).click();
  const screen = page.getByRole("img", { name: "내 브라우저 화면" });
  await expect(screen).toBeVisible();
  return screen;
}

function ofType(inputs: Input[], type: string): Input[] {
  return inputs.filter((input) => input.type === type);
}

test("화면을 열어 누르고 글자를 넣고 탭을 고르면 입력이 가고 닫힘을 알린다", async ({
  page,
}) => {
  const fake = await fakeScreen(page);
  const screen = await openScreen(
    page,
    "/browser?url=https%3A%2F%2Fexample.com%2Flogin",
  );

  expect(new URL(fake.screenUrls[0]!).searchParams.get("url")).toBe(
    "https://example.com/login",
  );
  await expect(page.getByRole("textbox", { name: "주소" })).toHaveValue(
    "https://example.com/login",
  );
  await expect
    .poll(() => ofType(fake.inputs, "resize").length)
    .toBeGreaterThan(0);
  const resize = ofType(fake.inputs, "resize")[0]!;
  expect(resize.height).toBe(Math.round((resize.width as number) * 1.5));

  await screen.click();
  await expect
    .poll(() => ofType(fake.inputs, "mouse").map((input) => input.action))
    .toEqual(["down", "up"]);
  for (const input of ofType(fake.inputs, "mouse")) {
    expect(input.x as number).toBeCloseTo(0.5, 1);
    expect(input.y as number).toBeCloseTo(0.5, 1);
  }

  await page.keyboard.insertText("안녕하세요");
  await expect
    .poll(() => ofType(fake.inputs, "text"))
    .toEqual([{ type: "text", text: "안녕하세요" }]);
  await page.keyboard.press("Enter");
  await expect
    .poll(() => ofType(fake.inputs, "key"))
    .toEqual([{ type: "key", key: "Enter" }]);

  await page.getByRole("combobox", { name: "탭" }).selectOption("B2");
  await expect
    .poll(() => ofType(fake.inputs, "tab"))
    .toEqual([{ type: "tab", id: "B2" }]);

  // 첫 SSE 가 끝나면 화면이 다시 연다. 그 SSE 가 closed 를 보낸다.
  await expect.poll(() => fake.screenUrls.length).toBe(2);
  expect(new URL(fake.screenUrls[1]!).searchParams.get("url")).toBeNull();
  fake.close();
  await expect(page.getByText("다른 창에서 화면을 열었어요.")).toBeVisible();
  await expect(page.getByRole("button", { name: "다시 열기" })).toBeVisible();
});

test("터치로 누르면 누르기로, 세로로 끌면 휠로 간다", async ({ page }) => {
  const fake = await fakeScreen(page);
  const screen = await openScreen(page, "/browser");
  await expect
    .poll(() => ofType(fake.inputs, "resize").length)
    .toBeGreaterThan(0);
  const box = (await screen.boundingBox())!;
  const x = box.x + box.width / 2;
  const y = box.y + box.height / 2;
  const touch = (clientX: number, clientY: number) => ({
    pointerType: "touch",
    pointerId: 7,
    isPrimary: true,
    button: 0,
    clientX,
    clientY,
  });

  await screen.dispatchEvent("pointerdown", touch(x, y));
  await screen.dispatchEvent("pointerup", touch(x + 2, y + 2));
  await expect
    .poll(() => ofType(fake.inputs, "mouse").map((input) => input.action))
    .toEqual(["down", "up"]);
  for (const input of ofType(fake.inputs, "mouse")) {
    expect(input.x as number).toBeCloseTo(0.5, 2);
    expect(input.y as number).toBeCloseTo(0.5, 2);
  }

  await screen.dispatchEvent("pointerdown", touch(x, y + 100));
  await screen.dispatchEvent("pointermove", touch(x, y));
  await screen.dispatchEvent("pointerup", touch(x, y));
  await expect.poll(() => ofType(fake.inputs, "wheel").length).toBe(1);
  const scroll = ofType(fake.inputs, "wheel")[0]!;
  // 위로 끌었으므로 아래로 굴린다. 끈 거리를 프레임 높이(600)의 CSS 픽셀로 바꾼다.
  expect(
    Math.abs((scroll.deltaY as number) - (100 * 600) / box.height),
  ).toBeLessThanOrEqual(1);
  expect(ofType(fake.inputs, "mouse")).toHaveLength(2);
});
