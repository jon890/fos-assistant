import { createHash } from "node:crypto";
import type {
  Locator,
  Page,
  TestInfo,
} from "../../web/node_modules/@playwright/test/index.js";
import playwright from "../../web/node_modules/@playwright/test/index.js";
import { RUN_ID } from "./settings.ts";

const { expect } = playwright;

/** 같은 검사의 준비와 정리가 같은 주소를 쓰되, 폭·반복·실행이 바뀌면 새 사용자를 만든다. */
export function isolatedUser(testInfo: TestInfo, prefix: string) {
  const namespace = createHash("sha256")
    .update(
      `${RUN_ID}:${testInfo.testId}:${testInfo.project.name}:${testInfo.repeatEachIndex}:${testInfo.retry}`,
    )
    .digest("hex")
    .slice(0, 16);
  return {
    email: `${prefix}-${namespace}@example.com`,
    name: "브라우저 검사 사용자",
  };
}

/** HTTP 응답의 머리만 도착한 상태와 본문까지 읽힌 상태를 구분한다. SSE에는 쓰지 않는다. */
export async function clickAndWaitForResponse(
  page: Page,
  target: Locator,
  method: string,
  pathname: RegExp,
) {
  const responsePromise = page.waitForResponse(
    (candidate) =>
      candidate.request().method() === method &&
      pathname.test(new URL(candidate.url()).pathname),
  );
  await target.click();
  const response = await responsePromise;
  expect(await response.finished(), "상태를 바꾸는 응답이 끊겼다").toBeNull();
  expect(
    response.ok(),
    `${method} 요청이 실패했다: ${response.status()}`,
  ).toBeTruthy();
  return response;
}

export const FIXED_BROWSER_NOW = new Date("2026-10-07T03:00:00Z");

/** 날짜만 고정하고 폴링과 애니메이션의 타이머는 계속 흐르게 한다. */
export async function fixBrowserTime(page: Page, at = FIXED_BROWSER_NOW) {
  await page.clock.setFixedTime(at);
}

/** 주소 변경과 조작할 단추의 준비를 확인한 뒤, 진행 중인 화면 전환의 스냅샷이 사라질 때까지 기다린다. */
export async function waitForViewTransition(page: Page) {
  await page.waitForFunction(() =>
    document
      .getAnimations()
      .every(
        (animation) =>
          !(
            animation.effect instanceof KeyframeEffect &&
            animation.effect.pseudoElement?.startsWith("::view-transition") &&
            animation.playState !== "finished"
          ),
      ),
  );
}
