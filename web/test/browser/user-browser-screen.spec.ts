import type {
  Locator,
  Page,
} from "../../node_modules/@playwright/test/index.js";
import { expect, test } from "./fixtures.ts";
import { FRAME, fakeScreen, type Input } from "./fake-screen.ts";

const TABS = [
  { id: "A1", title: "로그인", url: "https://example.com/login", active: true },
  { id: "B2", title: "팝업", url: "https://example.com/popup", active: false },
];


type Fake = Awaited<ReturnType<typeof fakeScreen>>;

/** CSS가 가운데 그린 이미지 영역이다. 입력은 이 영역 안의 자리를 누른다. */
async function drawnBox(screen: Locator) {
  await expect
    .poll(() => screen.evaluate((img: HTMLImageElement) => img.naturalWidth))
    .toBeGreaterThan(0);
  return screen.evaluate((img: HTMLImageElement) => {
    const rect = img.getBoundingClientRect();
    const scale = Math.min(
      rect.width / img.naturalWidth,
      rect.height / img.naturalHeight,
    );
    const width = img.naturalWidth * scale;
    const height = img.naturalHeight * scale;
    return {
      x: rect.x + (rect.width - width) / 2,
      y: rect.y + (rect.height - height) / 2,
      width,
      height,
    };
  });
}

test("화면 폭을 넓히고 전체 화면에서 크기를 맞춘 뒤 닫는다", async ({
  page,
}) => {
  const fake = await fakeScreen(page);
  const screen = await openScreen(page, fake);
  const initial = (await screen.boundingBox())!;
  expect(initial.width).toBeGreaterThan(
    page.viewportSize()!.width > 700 ? 672 : 300,
  );
  expect(initial.height).toBeLessThan(page.viewportSize()!.height);
  await page.getByRole("button", { name: "전체 화면", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "로그인 화면" })).toBeVisible();
  await expect
    .poll(async () => {
      const size = await screen.evaluate((img) => ({
        width: img.parentElement!.clientWidth,
        height: img.parentElement!.clientHeight,
      }));
      const resize = ofType(fake.inputs, "resize").at(-1);
      return (
        resize?.width === Math.min(1600, Math.max(320, size.width)) &&
        resize?.height === Math.min(2000, Math.max(320, size.height))
      );
    })
    .toBe(true);
  await page.getByRole("button", { name: "전체 화면 닫기" }).click();
  await expect(page.getByRole("dialog", { name: "로그인 화면" })).toHaveCount(
    0,
  );
  await expect(
    page.getByRole("button", { name: "전체 화면", exact: true }),
  ).toBeFocused();
});

test("전체 화면 API 가 막혀도 오버레이를 열고 Escape 로 닫는다", async ({
  page,
}) => {
  await page.addInitScript(() => {
    Element.prototype.requestFullscreen = () =>
      Promise.reject(new Error("unsupported"));
  });
  const fake = await fakeScreen(page);
  await openScreen(page, fake);
  await page.getByRole("button", { name: "전체 화면", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "로그인 화면" });
  await expect(dialog).toBeVisible();
  const box = (await dialog.boundingBox())!;
  expect(box.x).toBe(0);
  expect(box.width).toBe(page.viewportSize()!.width);
  expect(box.height).toBe(page.viewportSize()!.height);
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect
    .poll(() =>
      page.locator("dialog").evaluate((element) => element.matches(":modal")),
    )
    .toBe(false);
  await page.getByRole("button", { name: "전체 화면", exact: true }).click();
  await expect(dialog).toBeVisible();
  await page.getByRole("button", { name: "전체 화면 닫기" }).click();
  await expect(dialog).toHaveCount(0);
});

test("1920 폭과 크기 상한 밖에서도 프레임 비율과 클릭 좌표를 지킨다", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1920, height: 1080 });
  await page.addInitScript(() => {
    Element.prototype.requestFullscreen = () =>
      Promise.reject(new Error("unsupported"));
  });
  const fake = await fakeScreen(page);
  const screen = await openScreen(page, fake);
  await page.getByRole("button", { name: "전체 화면", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "로그인 화면" })).toBeVisible();
  const area = screen.locator("..");
  await expect
    .poll(() => area.evaluate((el) => el.clientWidth))
    .toBeGreaterThan(1600);
  await expect
    .poll(() => ofType(fake.inputs, "resize").at(-1)?.width)
    .toBe(1600);

  // 원격 크기 변경을 기다리는 동안 이전 400x600 프레임도 늘어나지 않는다.
  await expect(screen).toHaveCSS("object-fit", "contain");
  await expect(screen).toHaveCSS("object-position", "50% 50%");
  let drawn = await drawnBox(screen);
  expect(drawn.width / drawn.height).toBeCloseTo(400 / 600, 6);
  const element = (await screen.boundingBox())!;
  expect(drawn.x - element.x).toBeCloseTo((element.width - drawn.width) / 2, 6);
  await page.mouse.click(element.x + 2, element.y + element.height / 2);
  await page.mouse.click(
    drawn.x + drawn.width / 4,
    drawn.y + drawn.height * 0.75,
  );
  await expect.poll(() => ofType(fake.inputs, "mouse").length).toBe(2);

  // 실제 JPEG도 바꿔 디코딩된 프레임의 비율과 좌표를 확인한다.
  const data = await page.evaluate(() => {
    const canvas = document.createElement("canvas");
    canvas.width = 1600;
    canvas.height = 900;
    canvas.getContext("2d")!.fillRect(0, 0, 1600, 900);
    return canvas.toDataURL("image/jpeg").split(",")[1];
  });
  await fake.push([["frame", { data, width: 1600, height: 900 }]]);
  await expect
    .poll(() => screen.evaluate((img: HTMLImageElement) => img.naturalWidth))
    .toBe(1600);
  drawn = await drawnBox(screen);
  expect(drawn.width / drawn.height).toBeCloseTo(1600 / 900, 6);
  await page.mouse.click(
    drawn.x + drawn.width / 4,
    drawn.y + drawn.height * 0.75,
  );
  await expect.poll(() => ofType(fake.inputs, "mouse").length).toBe(4);

  // 높이도 상한을 넘는 칸에서 위아래 여백을 뺀 좌표를 확인한다.
  await area.evaluate((el) => {
    el.style.height = "2400px";
    el.style.flex = "none";
  });
  await expect
    .poll(() => ofType(fake.inputs, "resize").at(-1)?.height)
    .toBe(2000);
  drawn = await drawnBox(screen);
  expect(drawn.width / drawn.height).toBeCloseTo(1600 / 900, 6);
  const tall = (await screen.boundingBox())!;
  expect(drawn.y - tall.y).toBeGreaterThan(0);
  const event = {
    pointerType: "mouse",
    pointerId: 9,
    button: 0,
    clientX: drawn.x + drawn.width / 4,
    clientY: drawn.y + drawn.height * 0.75,
  };
  await screen.dispatchEvent("pointerdown", event);
  await screen.dispatchEvent("pointerup", event);
  await expect.poll(() => ofType(fake.inputs, "mouse").length).toBe(6);
  for (const input of ofType(fake.inputs, "mouse")) {
    expect(input.x).toBeCloseTo(0.25, 2);
    expect(input.y).toBeCloseTo(0.75, 2);
  }
});

test("휠과 터치 드래그는 실제 CDP 페이지의 스크롤 위치를 바꾼다", async ({
  page,
}) => {
  const remote = await page.context().newPage();
  await remote.setViewportSize({ width: 400, height: 600 });
  await remote.setContent(
    '<html><body><p>스크롤 시험</p><div style="height:10000px"></div></body></html>',
  );
  const cdp = await remote.context().newCDPSession(remote);
  const fake = await fakeScreen(page, async (input) => {
    if (input.type === "wheel")
      await cdp.send("Input.dispatchMouseEvent", {
        type: "mouseWheel",
        x: Number(input.x) * 400,
        y: Number(input.y) * 600,
        deltaX: 0,
        deltaY: Number(input.deltaY),
      });
  });
  const screen = await openScreen(page, fake);
  const box = await drawnBox(screen);
  await page.mouse.move(box.x + box.width / 2, box.y + 20);
  await page.mouse.wheel(0, 120);
  await expect
    .poll(() => remote.evaluate(() => window.scrollY))
    .toBeGreaterThan(0);
  const before = await remote.evaluate(() => window.scrollY);
  const touch = (clientY: number) => ({
    pointerType: "touch",
    pointerId: 7,
    button: 0,
    clientX: box.x + box.width / 2,
    clientY,
  });
  await screen.dispatchEvent("pointerdown", touch(box.y + 140));
  await screen.dispatchEvent("pointermove", touch(box.y + 40));
  await screen.dispatchEvent("pointerup", touch(box.y + 40));
  await expect
    .poll(() => remote.evaluate(() => window.scrollY))
    .toBeGreaterThan(before);
  await remote.close();
});

test("휠은 본문을 움직이지 않고 단추와 페이지 키도 원격으로 간다", async ({
  page,
}) => {
  const fake = await fakeScreen(page);
  const screen = await openScreen(page, fake);
  await screen.click();
  const scrollTop = await page
    .locator("main")
    .evaluate((main) => main.scrollTop);
  const box = await drawnBox(screen);
  await page.mouse.move(box.x + box.width / 2, box.y + 20);
  await page.mouse.wheel(0, 120);
  await expect.poll(() => ofType(fake.inputs, "wheel").length).toBe(1);
  expect(await page.locator("main").evaluate((main) => main.scrollTop)).toBe(
    scrollTop,
  );
  await page.getByRole("button", { name: "아래로", exact: true }).click();
  await page.getByRole("button", { name: "위로", exact: true }).click();
  await expect
    .poll(() => ofType(fake.inputs, "wheel").map((input) => input.deltaY))
    .toEqual([120, 600, -600]);
  await page.getByRole("button", { name: "처음으로" }).click();
  await page.getByRole("button", { name: "끝으로" }).click();
  await expect
    .poll(() => ofType(fake.inputs, "scroll"))
    .toEqual([
      { type: "scroll", action: "top" },
      { type: "scroll", action: "bottom" },
    ]);
  await page.getByLabel("화면에 글자 넣기").focus();
  await page.keyboard.press("PageUp");
  await page.keyboard.press("PageDown");
  await page.keyboard.press("Space");
  await expect
    .poll(() => ofType(fake.inputs, "key").map((input) => input.key))
    .toEqual(["PageUp", "PageDown", "Space"]);
  const down = page.getByRole("button", { name: "아래로", exact: true });
  await down.hover();
  await page.mouse.down();
  await expect
    .poll(() => ofType(fake.inputs, "wheel").length)
    .toBeGreaterThanOrEqual(6);
  await page.mouse.up();
  const count = ofType(fake.inputs, "wheel").length;
  await page.waitForTimeout(450);
  expect(ofType(fake.inputs, "wheel")).toHaveLength(count);
});

function ofType(inputs: Input[], type: string): Input[] {
  return inputs.filter((input) => input.type === type);
}

async function openScreen(page: Page, fake: Fake, path = "/browser") {
  await page.goto(path);
  await page.getByRole("button", { name: "화면 열기" }).click();
  await expect.poll(fake.opens).toHaveLength(1);
  await fake.push([
    ["frame", { data: FRAME, width: 400, height: 600 }],
    ["tabs", TABS],
  ]);
  const screen = page.getByRole("img", { name: "내 브라우저 화면" });
  await expect(screen).toBeVisible();
  await expect
    .poll(() => ofType(fake.inputs, "resize").length)
    .toBeGreaterThan(0);
  return screen;
}

test("화면을 열어 누르고 글자를 넣고 굴리고 탭을 고르면 입력이 가고 닫힘을 알린다", async ({
  page,
}) => {
  const fake = await fakeScreen(page);
  const screen = await openScreen(
    page,
    fake,
    "/browser?url=https%3A%2F%2FEXAMPLE.com%2Flogin",
  );

  // 시작 주소는 정규화해 첫 연결에만 넘긴다.
  expect(new URL((await fake.opens())[0]!).searchParams.get("url")).toBe(
    "https://example.com/login",
  );
  await expect(page.getByRole("textbox", { name: "주소" })).toHaveValue(
    "https://example.com/login",
  );
  const resize = ofType(fake.inputs, "resize")[0]!;
  expect(resize.height).toBeGreaterThanOrEqual(320);
  expect(resize.height).toBeLessThanOrEqual(page.viewportSize()!.height);

  // 클릭 전에 Playwright가 위치 안정과 스크롤을 기다린다. 가운데는 여백과 관계없이 같다.
  await screen.click();
  await expect
    .poll(() => ofType(fake.inputs, "mouse").map((input) => input.action))
    .toEqual(["down", "up"]);
  for (const input of ofType(fake.inputs, "mouse")) {
    expect(input.x as number).toBeCloseTo(0.5, 1);
    expect(input.y as number).toBeCloseTo(0.5, 2);
  }

  await page.keyboard.insertText("안녕하세요");
  await page.keyboard.press("Enter");
  await page.keyboard.insertText("가".repeat(501));
  await expect
    .poll(() =>
      fake.inputs
        .filter((input) => input.type === "text" || input.type === "key")
        .map((input) => input.key ?? (input.text as string).length),
    )
    .toEqual([5, "Enter", 500, 1]);

  const wheelBox = await drawnBox(screen);
  await page.mouse.move(wheelBox.x + wheelBox.width / 2, wheelBox.y + 20);
  await page.mouse.wheel(0, 120);
  await expect
    .poll(() => ofType(fake.inputs, "wheel").map((input) => input.deltaY))
    .toEqual([120]);

  await page.getByRole("combobox", { name: "탭" }).selectOption("B2");
  await expect
    .poll(() => ofType(fake.inputs, "tab"))
    .toEqual([{ type: "tab", id: "B2" }]);

  await fake.push([["closed", { reason: "stopped" }]]);
  await expect(page.getByText("브라우저가 꺼졌어요.")).toBeVisible();
  await page.getByRole("button", { name: "다시 열기" }).click();
  await expect.poll(fake.opens).toHaveLength(2);
  expect(new URL((await fake.opens())[1]!).searchParams.get("url")).toBeNull();
  await fake.push([["frame", { data: FRAME, width: 400, height: 600 }]]);
  await expect(screen).toBeVisible();
  await fake.push([["closed", { reason: "timeout" }]]);
  await expect(page.getByText("오래 쓰지 않아 화면을 닫았어요.")).toBeVisible();
});

test("터치로 누르면 누르기로, 세로로 끌면 휠로 간다", async ({ page }) => {
  const fake = await fakeScreen(page);
  const screen = await openScreen(page, fake);
  const box = await drawnBox(screen);
  const x = box.x + box.width / 2;
  const y = box.y + box.height / 2;
  const touch = (clientX: number, clientY: number, pointerId = 7) => ({
    pointerType: "touch",
    pointerId,
    isPrimary: pointerId === 7,
    button: 0,
    clientX,
    clientY,
  });

  await screen.dispatchEvent("pointerdown", touch(x, y));
  // 두 번째 손가락은 따르지 않는다.
  await screen.dispatchEvent("pointerdown", touch(x - 50, y - 50, 8));
  await screen.dispatchEvent("pointerup", touch(x - 50, y - 50, 8));
  await screen.dispatchEvent("pointerup", touch(x + 2, y + 2));
  await expect
    .poll(() => ofType(fake.inputs, "mouse").map((input) => input.action))
    .toEqual(["down", "up"]);
  for (const input of ofType(fake.inputs, "mouse")) {
    expect(input.x as number).toBeCloseTo(0.5, 1);
    expect(input.y as number).toBeCloseTo(0.5, 1);
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

test("말없이 끊기면 다시 열고, 입력이 닫힌 화면에 닿으면 닫힘을 알린다", async ({
  page,
}) => {
  const fake = await fakeScreen(page);
  const screen = await openScreen(page, fake);

  // 첫 재시도는 Control Plane 이 잠깐 닿지 않아 502 다. 닫지 않고 다음 대기 뒤 다시 연다.
  await fake.fail(502, { code: "INTERNAL_ERROR", message: "unreachable" });
  await fake.end();
  await expect.poll(fake.opens, { timeout: 10_000 }).toHaveLength(3);
  await expect(page.getByText("다시 열어 주세요.")).toHaveCount(0);
  await fake.push([["frame", { data: FRAME, width: 400, height: 600 }]]);
  await expect(page.getByText("다시 열어 주세요.")).toHaveCount(0);

  fake.state.reply = {
    status: 409,
    body: { code: "BROWSER_SCREEN_CLOSED", message: "no open browser screen" },
  };
  await screen.click();
  await expect(
    page.getByText("화면이 닫혔어요. 다시 열어 주세요."),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "다시 열기" })).toBeVisible();
  await expect(page.getByText("BROWSER_SCREEN_CLOSED")).toHaveCount(0);
});

test("화면을 열지 못하면 할 일만 알리고 다시 열 수 있다", async ({ page }) => {
  const fake = await fakeScreen(page);
  await page.goto("/browser");
  await fake.fail(502, {
    code: "BROWSER_START_FAILED",
    message: "browser did not answer",
  });
  await page.getByRole("button", { name: "화면 열기" }).click();

  await expect(
    page.getByText("브라우저를 켜지 못했어요. 잠시 뒤 다시 켜 주세요."),
  ).toBeVisible();
  await expect(page.getByText("BROWSER_START_FAILED")).toHaveCount(0);
  await page.getByRole("button", { name: "다시 열기" }).click();
  await expect.poll(fake.opens).toHaveLength(2);
});

// CDP 로 입력기를 흉내 내므로 chromium 에서만 돈다. 두 프로젝트 모두 chromium 이다.
test("한글은 조합이 끝난 뒤 한 번 보내고, 빈 입력칸의 지우기는 Backspace 다", async ({
  page,
}) => {
  const fake = await fakeScreen(page);
  const screen = await openScreen(page, fake);
  await screen.click();
  const cdp = await page.context().newCDPSession(page);

  for (const syllable of [
    ["ㅎ", "하", "한"],
    ["ㄱ", "그", "글"],
  ]) {
    for (const text of syllable) {
      await cdp.send("Input.imeSetComposition", {
        text,
        selectionStart: text.length,
        selectionEnd: text.length,
      });
    }
    await cdp.send("Input.insertText", { text: syllable.at(-1)! });
  }
  // 조합 중 글자(ㅎ, 하, ㄱ, 그)는 보내지 않는다. 이어 조합한 음절은 한 번에 갈 수 있다.
  await expect
    .poll(() =>
      ofType(fake.inputs, "text")
        .map((input) => input.text)
        .join(""),
    )
    .toBe("한글");
  const keys = page.getByLabel("화면에 글자 넣기");
  await expect(keys).toHaveValue("");

  await keys.evaluate((element) =>
    element.dispatchEvent(
      new InputEvent("beforeinput", {
        inputType: "deleteContentBackward",
        bubbles: true,
        cancelable: true,
      }),
    ),
  );
  await expect
    .poll(() => ofType(fake.inputs, "key"))
    .toEqual([{ type: "key", key: "Backspace" }]);
});

test("화면 넘기기가 가리킨 탭이 처음 목록에 있으면 그리로 옮기고 다시 열 때는 옮기지 않는다", async ({
  page,
}) => {
  const fake = await fakeScreen(page);
  await openScreen(page, fake, "/browser?tab=B2");

  await expect
    .poll(() => ofType(fake.inputs, "tab"))
    .toEqual([{ type: "tab", id: "B2" }]);
  // 연결이 끊겨 다시 열면 처음 탭 목록이 다시 온다. 이때는 옮기지 않는다.
  await fake.end();
  await expect.poll(fake.opens, { timeout: 10_000 }).toHaveLength(2);
  await fake.push([["tabs", TABS]]);
  // 사건은 온 순서대로 처리된다. 뒤따른 확인 창에 답한 입력이 닿은 뒤에 탭 입력을 센다.
  await fake.push([["dialog", { open: true, type: "alert", message: "" }]]);
  await page
    .getByRole("alertdialog", { name: "사이트 확인 창" })
    .getByRole("button", { name: "확인" })
    .click();
  await expect
    .poll(() => ofType(fake.inputs, "dialog"))
    .toEqual([{ type: "dialog", action: "accept" }]);
  expect(ofType(fake.inputs, "tab")).toHaveLength(1);
});

test("사이트의 확인 창을 보이고 고른 답을 보내며, 창이 닫히면 거둔다", async ({
  page,
}) => {
  const fake = await fakeScreen(page);
  await openScreen(page, fake);

  await fake.push([
    ["dialog", { open: true, type: "confirm", message: "삭제하시겠습니까?" }],
  ]);
  const dialog = page.getByRole("alertdialog", { name: "사이트 확인 창" });
  await expect(dialog).toContainText("사이트가 확인을 묻고 있어요");
  await expect(dialog).toContainText("삭제하시겠습니까?");
  await dialog.getByRole("button", { name: "취소" }).click();
  await expect
    .poll(() => ofType(fake.inputs, "dialog"))
    .toEqual([{ type: "dialog", action: "dismiss" }]);
  await fake.push([["dialog", { open: false }]]);
  await expect(dialog).toHaveCount(0);

  await fake.push([["dialog", { open: true, type: "alert", message: "" }]]);
  await expect(dialog.getByRole("button", { name: "취소" })).toHaveCount(0);
  await dialog.getByRole("button", { name: "확인" }).click();
  await expect
    .poll(() => ofType(fake.inputs, "dialog").map((input) => input.action))
    .toEqual(["dismiss", "accept"]);
});
