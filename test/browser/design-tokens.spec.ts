import type { Locator, Page, TestInfo } from "../../web/node_modules/@playwright/test/index.js";
import { contrast, parseRgb } from "./color.ts";
import { expect, test } from "./fixtures.ts";

/** `globals.css` 가 두 밝기 모드에서 모두 값을 가져야 하는 색 토큰이다. 이름은 ADR-023 과 ADR-051 의 표를 따른다. */
const TOKENS = [
  "--background",
  "--foreground",
  "--muted",
  "--muted-foreground",
  "--accent",
  "--accent-foreground",
  "--secondary",
  "--secondary-foreground",
  "--card",
  "--card-foreground",
  "--popover",
  "--popover-foreground",
  "--border",
  "--input",
  "--ring",
  "--primary",
  "--primary-foreground",
  "--primary-strong",
  "--primary-soft",
  "--destructive",
  "--destructive-foreground",
  "--foreground-soft",
  "--primary-soft-foreground",
  "--signal",
  "--pill",
  "--pill-foreground",
  "--destructive-soft",
  "--success",
  "--success-soft",
  "--warning",
  "--warning-soft",
  "--info",
  "--info-soft",
];

/** 옮기기 전의 이름이다. 새 이름의 별칭으로도 남기지 않는다. */
const RETIRED_TOKENS = ["--brand", "--brand-strong", "--brand-soft", "--on-brand", "--surface", "--surface-raised", "--danger"];

type Theme = "light" | "dark";

/** `html` 의 `dark` 클래스를 바꿔 그 밝기 모드의 변수 값을 읽는다. 끝나면 밝음으로 돌려 둔다. */
async function readVariables(page: Page, names: string[]): Promise<Record<Theme, Record<string, string>>> {
  return page.locator("html").evaluate((html, variableNames) => {
    const read = () => {
      const styles = getComputedStyle(html);
      return Object.fromEntries(variableNames.map((name) => [name, styles.getPropertyValue(name).trim()]));
    };
    html.classList.remove("dark");
    const light = read();
    html.classList.add("dark");
    const dark = read();
    html.classList.remove("dark");
    return { light, dark };
  }, names);
}

async function openSidebar(page: Page, testInfo: TestInfo): Promise<void> {
  if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();
}

test("두 밝기 모드에서 새 색 토큰이 모두 값을 갖고 옛 이름은 남지 않는다", async ({ page }) => {
  await page.goto("/");
  const values = await readVariables(page, [...TOKENS, ...RETIRED_TOKENS]);

  for (const theme of ["light", "dark"] as const) {
    const empty = TOKENS.filter((name) => values[theme][name] === "");
    expect(empty, `${theme} 에서 값이 빈 토큰`).toEqual([]);
    const retired = RETIRED_TOKENS.filter((name) => values[theme][name] !== "");
    expect(retired, `${theme} 에서 남아 있는 옛 토큰`).toEqual([]);
  }
});

test("흐린 바탕은 muted, 흐린 글자는 muted-foreground 로 그린다", async ({ page }, testInfo) => {
  await page.addInitScript(() => localStorage.setItem("theme", "light"));
  await page.goto("/");
  await openSidebar(page, testInfo);

  const sidebar = page.getByTestId("new-conversation-link").locator("xpath=..");
  const displayName = page.locator('span[title="브라우저 테스트"]');
  await expect(displayName).toBeVisible();

  const colors = await sidebar.evaluate((element, nameElement) => {
    const root = getComputedStyle(document.documentElement);
    return {
      sidebarBackground: getComputedStyle(element).backgroundColor,
      mutedText: getComputedStyle(nameElement!).color,
      muted: root.getPropertyValue("--muted").trim(),
      mutedForeground: root.getPropertyValue("--muted-foreground").trim(),
    };
  }, await displayName.elementHandle());

  expect(colors.muted).not.toBe(colors.mutedForeground);
  expect(parseRgb(colors.sidebarBackground), "사이드바 바탕").toEqual(parseRgb(colors.muted));
  expect(parseRgb(colors.mutedText), "사이드바의 사용자 이름 글자").toEqual(parseRgb(colors.mutedForeground));
});

test("두 밝기 모드에서 destructive 가 그 위 글자와 화면 바탕 모두와 대비 4.5 이상이다", async ({ page }) => {
  await page.goto("/");
  const values = await readVariables(page, ["--destructive", "--destructive-foreground", "--background"]);

  for (const theme of ["light", "dark"] as const) {
    const { "--destructive": destructive, "--destructive-foreground": foreground, "--background": background } = values[theme];
    expect(contrast(foreground, destructive), `${theme}: ${foreground} 글자 / ${destructive} 바탕`).toBeGreaterThanOrEqual(4.5);
    expect(contrast(destructive, background), `${theme}: ${destructive} 글자 / ${background} 바탕`).toBeGreaterThanOrEqual(4.5);
  }
});

/** 글자나 선, 그것이 놓이는 바탕, 넘어야 하는 대비다. 글자는 4.5, 입력칸 테두리와 초점 테두리는 3 이다. */
const CONTRAST_PAIRS: { foregrounds: string[]; backgrounds: string[]; minimum: number }[] = [
  {
    foregrounds: ["--foreground", "--foreground-soft", "--muted-foreground"],
    backgrounds: ["--background", "--card", "--muted"],
    minimum: 4.5,
  },
  { foregrounds: ["--primary-foreground"], backgrounds: ["--primary", "--primary-strong"], minimum: 4.5 },
  { foregrounds: ["--primary-soft-foreground"], backgrounds: ["--primary-soft"], minimum: 4.5 },
  ...["--success", "--warning", "--info", "--destructive"].map((name) => ({
    foregrounds: [name],
    backgrounds: [`${name}-soft`, "--background", "--card"],
    minimum: 4.5,
  })),
  { foregrounds: ["--pill-foreground"], backgrounds: ["--pill"], minimum: 4.5 },
  { foregrounds: ["--input", "--ring"], backgrounds: ["--background", "--card"], minimum: 3 },
];

test("두 밝기 모드에서 글자와 선이 제 바탕과 기준 대비를 넘는다", async ({ page }) => {
  await page.goto("/");
  const values = await readVariables(page, TOKENS);

  for (const theme of ["light", "dark"] as const) {
    const low = CONTRAST_PAIRS.flatMap(({ foregrounds, backgrounds, minimum }) =>
      foregrounds.flatMap((foreground) =>
        backgrounds
          .map((background) => ({ foreground, background, ratio: contrast(values[theme][foreground], values[theme][background]) }))
          .filter(({ ratio }) => ratio < minimum)
          .map(({ foreground, background, ratio }) => `${foreground} / ${background} = ${ratio.toFixed(2)} (기준 ${minimum})`),
      ),
    );
    expect(low, `${theme} 에서 대비가 모자란 짝`).toEqual([]);
  }
});

test("밝음에서 사이드바 메뉴 글자가 사이드바 바탕과 대비 4.5 이상이다", async ({ page }, testInfo) => {
  await page.addInitScript(() => localStorage.setItem("theme", "light"));
  await page.goto("/");
  await openSidebar(page, testInfo);

  // 좁은 폭은 서랍, 넓은 폭은 aside 가 사이드바다.
  const sidebar =
    testInfo.project.name === "mobile"
      ? page.locator('[data-slot="sheet-content"][data-side="left"]')
      : page.locator('aside[aria-label="사이드바"]');
  const surface = sidebar.getByTestId("new-conversation-link").locator("xpath=..");
  const links = sidebar.getByRole("navigation", { name: "주요 화면" }).getByRole("link");
  await expect(links.first()).toBeVisible();

  const background = await surface.evaluate((element) => getComputedStyle(element).backgroundColor);
  expect(background, "사이드바 바탕이 칠해져 있어야 한다").not.toBe("rgba(0, 0, 0, 0)");
  const texts = await links.evaluateAll((elements) =>
    elements.map((element) => ({ label: element.textContent ?? "", color: getComputedStyle(element).color })),
  );
  expect(texts.length, "사이드바 메뉴 링크 수").toBeGreaterThan(0);
  for (const { label, color } of texts) {
    expect(contrast(color, background), `「${label}」 글자 ${color} / 사이드바 바탕 ${background}`).toBeGreaterThanOrEqual(4.5);
  }
});

test("두 밝기 모드에서 대화 입력창의 테두리는 input 색이다", async ({ page }) => {
  await page.goto("/");
  const shell = page.getByTestId("composer-shell");
  await expect(shell).toBeVisible();

  const colors = await shell.evaluate((node) => {
    const html = document.documentElement;
    // 초점이 안에 있으면 테두리가 초점 색이 된다. 초점을 빼고 색 바뀜의 transition 을 끈 뒤 읽는다.
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
    (node as HTMLElement).style.transition = "none";
    const read = () => ({
      width: getComputedStyle(node).borderTopWidth,
      color: getComputedStyle(node).borderTopColor,
      token: getComputedStyle(html).getPropertyValue("--input").trim(),
      border: getComputedStyle(html).getPropertyValue("--border").trim(),
    });
    html.classList.remove("dark");
    const light = read();
    html.classList.add("dark");
    const dark = read();
    html.classList.remove("dark");
    return { light, dark };
  });

  for (const theme of ["light", "dark"] as const) {
    const { width, color, token, border } = colors[theme];
    expect(parseRgb(token), `${theme}: --input 과 --border 는 다른 색이어야 이 검사가 뜻이 있다`).not.toEqual(parseRgb(border));
    expect(width, `${theme} 대화 입력창 테두리 두께`).toBe("1px");
    expect(parseRgb(color), `${theme} 대화 입력창 테두리가 --input(${token}) 이어야 한다`).toEqual(parseRgb(token));
  }
});

test("대화 지우기 확인 창의 지우기 단추는 destructive 바탕에 destructive-foreground 글자다", async ({ page }, testInfo) => {
  const title = `색 토큰 지우기 ${testInfo.project.name} ${Date.now()}`;
  const created = await page.request.post("/api/chat", { data: { text: title, agentCode: "browser" } });
  expect(created.ok()).toBeTruthy();
  await page.goto("/");
  await openSidebar(page, testInfo);
  const nav = page.getByRole("navigation", { name: "대화 목록" });
  await nav.getByRole("button", { name: `${title} 메뉴` }).click();
  await page.getByRole("menuitem", { name: "지우기" }).click();
  const confirm = page.getByRole("alertdialog", { name: "대화 지우기" }).getByRole("button", { name: "지우기" });
  await expect(confirm).toBeVisible();

  const colors = await confirm.evaluate((button) => {
    const html = document.documentElement;
    // Button 은 색 바뀜을 transition 으로 보인다. 밝기를 바꾼 바로 뒤에 읽으면 바뀌는 중의 색이 나오므로 끈다.
    (button as HTMLElement).style.transition = "none";
    const read = () => {
      const root = getComputedStyle(html);
      const own = getComputedStyle(button);
      return {
        background: own.backgroundColor,
        color: own.color,
        destructive: root.getPropertyValue("--destructive").trim(),
        destructiveForeground: root.getPropertyValue("--destructive-foreground").trim(),
      };
    };
    html.classList.remove("dark");
    const light = read();
    html.classList.add("dark");
    const dark = read();
    html.classList.remove("dark");
    return { light, dark };
  });

  for (const theme of ["light", "dark"] as const) {
    const { background, color, destructive, destructiveForeground } = colors[theme];
    expect(parseRgb(background), `${theme} 지우기 단추 바탕`).toEqual(parseRgb(destructive));
    expect(parseRgb(color), `${theme} 지우기 단추 글자`).toEqual(parseRgb(destructiveForeground));
  }
});

test("보내기 단추에 마우스를 올리면 바탕이 primary-strong 이다", async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem("theme", "light"));
  await page.goto("/");
  // 빈 입력이면 보내기가 잠겨 마우스 올림 모양이 그려지지 않는다.
  await page.getByRole("textbox", { name: "메시지" }).fill("마우스 올림 확인");
  const send = page.getByRole("button", { name: "보내기" });
  await expect(send).toBeEnabled();

  const primaryStrong = await page.locator("html").evaluate((html) => getComputedStyle(html).getPropertyValue("--primary-strong").trim());
  const primary = await page.locator("html").evaluate((html) => getComputedStyle(html).getPropertyValue("--primary").trim());
  expect(parseRgb(primaryStrong), "primary-strong 과 primary 는 다른 색이어야 이 검사가 뜻이 있다").not.toEqual(parseRgb(primary));

  await send.hover();
  // 단추의 색 전환 효과가 끝날 때까지 기다린다.
  await expect
    .poll(async () => parseRgb(await send.evaluate((button) => getComputedStyle(button).backgroundColor)), {
      message: `보내기 단추 마우스 올림 바탕이 --primary-strong(${primaryStrong}) 이어야 한다`,
    })
    .toEqual(parseRgb(primaryStrong));
});

test("두 밝기 모드에서 비활성 보내기 단추는 muted 바탕에 muted-foreground 글자이고 투명도를 쓰지 않는다", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("textbox", { name: "메시지" })).toHaveValue("");
  const send = page.getByRole("button", { name: "보내기" });
  await expect(send).toBeDisabled();

  const colors = await send.evaluate((button) => {
    const html = document.documentElement;
    // Button 은 색 바뀜을 transition 으로 보인다. 밝기를 바꾼 바로 뒤에 읽으면 바뀌는 중의 색이 나오므로 끈다.
    (button as HTMLElement).style.transition = "none";
    const read = () => {
      const root = getComputedStyle(html);
      const own = getComputedStyle(button);
      return {
        background: own.backgroundColor,
        color: own.color,
        opacity: own.opacity,
        muted: root.getPropertyValue("--muted").trim(),
        mutedForeground: root.getPropertyValue("--muted-foreground").trim(),
        primary: root.getPropertyValue("--primary").trim(),
      };
    };
    html.classList.remove("dark");
    const light = read();
    html.classList.add("dark");
    const dark = read();
    html.classList.remove("dark");
    return { light, dark };
  });

  for (const theme of ["light", "dark"] as const) {
    const { background, color, opacity, muted, mutedForeground, primary } = colors[theme];
    expect(parseRgb(muted), `${theme}: --muted 와 --primary 는 다른 색이어야 이 검사가 뜻이 있다`).not.toEqual(parseRgb(primary));
    expect(parseRgb(background), `${theme} 비활성 보내기 단추 바탕이 --muted(${muted}) 이어야 한다`).toEqual(parseRgb(muted));
    expect(parseRgb(color), `${theme} 비활성 보내기 단추 글자가 --muted-foreground(${mutedForeground}) 이어야 한다`).toEqual(
      parseRgb(mutedForeground),
    );
    expect(opacity, `${theme} 비활성 보내기 단추의 opacity`).toBe("1");
  }
});

test("어두움 모드의 outline 단추는 테두리가 border 색이고 바탕이 비어 있다", async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem("theme", "dark"));
  await page.goto("/");
  await expect(page.locator("html")).toHaveClass(/\bdark\b/);
  const attach = page.getByRole("button", { name: "사진 첨부" });
  await expect(attach).toHaveAttribute("data-variant", "outline");

  const colors = await attach.evaluate((button) => {
    const own = getComputedStyle(button);
    return {
      border: own.borderTopColor,
      borderWidth: own.borderTopWidth,
      background: own.backgroundColor,
      token: getComputedStyle(document.documentElement).getPropertyValue("--border").trim(),
    };
  });

  expect(colors.borderWidth, "outline 단추 테두리 두께").toBe("1px");
  expect(parseRgb(colors.border), `outline 단추 테두리가 --border(${colors.token}) 이어야 한다`).toEqual(parseRgb(colors.token));
  expect(colors.background, "outline 단추 바탕은 투명해야 한다").toBe("rgba(0, 0, 0, 0)");
});

type BorderSide = "left" | "right";

/**
 * 두 밝기 모드에서 그 요소의 한쪽 테두리 색과 `--border` 를 읽는다. 끝나면 처음 밝기로 돌려 둔다.
 *
 * <p>Sheet 는 색 바뀜을 transition 으로 보인다. 밝기를 바꾼 바로 뒤에 읽으면 바뀌는 중의 색이 나오므로 끈다.
 */
async function readBorder(element: Locator, side: BorderSide) {
  return element.evaluate((node, borderSide) => {
    const html = document.documentElement;
    const wasDark = html.classList.contains("dark");
    (node as HTMLElement).style.transition = "none";
    const read = () => {
      const own = getComputedStyle(node);
      return {
        width: own.getPropertyValue(`border-${borderSide}-width`),
        color: own.getPropertyValue(`border-${borderSide}-color`),
        token: getComputedStyle(html).getPropertyValue("--border").trim(),
      };
    };
    html.classList.remove("dark");
    const light = read();
    html.classList.add("dark");
    const dark = read();
    html.classList.toggle("dark", wasDark);
    return { light, dark };
  }, side);
}

function expectBorderToken(colors: Awaited<ReturnType<typeof readBorder>>, name: string) {
  for (const theme of ["light", "dark"] as const) {
    const { width, color, token } = colors[theme];
    expect(width, `${theme} ${name} 테두리 두께`).toBe("1px");
    expect(parseRgb(color), `${theme} ${name} 테두리가 --border(${token}) 이어야 한다`).toEqual(parseRgb(token));
  }
}

test("두 밝기 모드에서 사이드바와 작업 과정 패널의 테두리는 border 색이다", async ({ page }, testInfo) => {
  await page.goto("/");
  await page.getByRole("radio", { name: "브라우저 비서" }).click();
  await page.getByRole("textbox", { name: "메시지" }).fill("테두리 색 검사");
  await page.getByRole("button", { name: "보내기" }).click();
  const block = page.locator('[data-testid="activity-block"][data-mode="saved"]').last();
  await expect(block).toBeVisible({ timeout: 30_000 });
  await block.getByTestId("activity-toggle").click();
  await block.getByTestId("activity-open-panel").click();
  const panel = page.getByTestId("activity-panel");
  await expect(panel).toBeVisible();
  // 좁은 폭은 Sheet, 넓은 폭은 대화 옆에 붙는 aside 다. 어느 쪽이든 왼쪽 테두리를 가진다.
  expectBorderToken(await readBorder(panel, "left"), "작업 과정 패널");

  if (testInfo.project.name === "mobile") {
    await panel.getByRole("button", { name: "작업 과정 닫기" }).click();
    await expect(panel).toHaveCount(0);
    await openSidebar(page, testInfo);
    const drawer = page.locator('[data-slot="sheet-content"][data-side="left"]');
    await expect(drawer).toBeVisible();
    expectBorderToken(await readBorder(drawer, "right"), "사이드바 서랍");
  } else {
    expectBorderToken(await readBorder(page.locator('aside[aria-label="사이드바"]'), "right"), "사이드바");
  }
});

/** 그 요소의 글자색과 바탕, 견줄 두 토큰의 값을 함께 읽는다. */
async function readSemanticColors(element: Locator, name: string) {
  return element.evaluate((node, tokenName) => {
    const own = getComputedStyle(node);
    const root = getComputedStyle(document.documentElement);
    return {
      color: own.color,
      background: own.backgroundColor,
      token: root.getPropertyValue(`--${tokenName}`).trim(),
      softToken: root.getPropertyValue(`--${tokenName}-soft`).trim(),
    };
  }, name);
}

test("실행 기록의 성공 배지는 글자가 success 색이고 바탕이 success-soft 다", async ({ page }, testInfo) => {
  await page.addInitScript(() => localStorage.setItem("theme", "light"));
  const response = await page.request.post("/api/chat", {
    data: { text: "성공 배지 색 검사", agentCode: "browser" },
  });
  expect(response.ok()).toBeTruthy();
  await page.goto("/usage?tab=executions");

  const records = page.getByTestId(testInfo.project.name === "mobile" ? "execution-cards" : "execution-table");
  const badge = records.locator('[data-slot="badge"]').filter({ hasText: /^성공$/ }).first();
  await expect(badge).toBeVisible();
  await expect(badge).toHaveAttribute("data-variant", "success");

  const colors = await readSemanticColors(badge, "success");
  expect(parseRgb(colors.color), `성공 배지 글자가 --success(${colors.token}) 이어야 한다`).toEqual(parseRgb(colors.token));
  expect(parseRgb(colors.background), `성공 배지 바탕이 --success-soft(${colors.softToken}) 이어야 한다`).toEqual(
    parseRgb(colors.softToken),
  );
});

test("실패한 turn 의 알림 상자는 destructive 색이고 아이콘을 하나 갖는다", async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem("theme", "light"));
  await page.goto("/");
  // 시작 사건 뒤에 실패 사건을 넣어 그 turn 을 실패로 끝낸다.
  await page.route("**/api/chat/stream", async (route) => {
    const response = await route.fetch();
    const raw = await response.text();
    const started = raw
      .split("\n")
      .find((line) => line.startsWith("data:") && JSON.parse(line.slice(5).trim()).type === "started");
    if (!started) throw new Error("started 사건을 찾지 못했다");
    await route.fulfill({
      status: 200,
      contentType: "text/event-stream",
      body: `${started}\n\ndata: {"type":"error","code":"HERMES_RUN_FAILED","message":"failed"}\n\n`,
    });
  });
  await page.getByRole("textbox", { name: "메시지" }).fill(`알림 상자 색 검사 ${Date.now()}`);
  await page.getByRole("button", { name: "보내기" }).click();

  const notice = page.getByTestId("turn-error").locator('[data-slot="notice"][data-variant="error"]');
  await expect(notice).toBeVisible();
  await expect(notice.locator("svg"), "알림 상자의 아이콘").toHaveCount(1);
  await expect(notice.locator("svg")).toHaveAttribute("aria-hidden", "true");

  const colors = await readSemanticColors(notice, "destructive");
  expect(parseRgb(colors.color), `알림 상자 글자가 --destructive(${colors.token}) 이어야 한다`).toEqual(parseRgb(colors.token));
  expect(parseRgb(colors.background), `알림 상자 바탕이 --destructive-soft(${colors.softToken}) 이어야 한다`).toEqual(
    parseRgb(colors.softToken),
  );
});
