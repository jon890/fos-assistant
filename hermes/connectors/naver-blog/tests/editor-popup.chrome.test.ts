import { afterAll, afterEach, beforeAll, expect, test } from "bun:test";
import { parseBody } from "../src/draft.ts";
import { fill } from "../src/editor/text.ts";
import { open } from "../src/editor/open.ts";
import { focusField } from "../src/editor/focus.ts";
import { filterPopupDiagnostics } from "../src/editor/popup.ts";
import { TITLE_SELECTOR } from "../src/editor/page.ts";
import {
  closeTab,
  type EditorPage,
  requireClearScreen,
} from "../src/editor/page.ts";
import { localChrome } from "./local-chrome.ts";

let browser: Awaited<ReturnType<typeof localChrome>>;
const cleanups: Array<() => Promise<unknown>> = [];
beforeAll(async () => {
  browser = await localChrome();
}, 15_000);
afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});
afterAll(async () => {
  await browser?.stop();
});

async function tab() {
  const { page, targetId } = await browser.tab({ stepSeconds: 0.6 });
  cleanups.push(async () => {
    page.close();
    await closeTab(browser.url, targetId);
  });
  return page;
}

/** 합성 알림을 만든다. 단추를 누르면 `window.clicked` 에 이름을 쌓고 `closes` 에 든 이름이면 알림을 없앤다. */
function popup(
  page: EditorPage,
  {
    cls,
    text,
    buttons,
    closes,
  }: {
    cls: string;
    text: string;
    buttons: string[];
    closes: string[];
  },
) {
  return page.js(`(() => {
    window.clicked = [];
    const dim = document.createElement('div');
    dim.className = 'popup-dim'; dim.style.cssText = 'position:fixed;inset:0;z-index:50';
    const box = document.createElement('div');
    box.className = ${JSON.stringify(cls)}; box.setAttribute('role', 'dialog');
    box.style.cssText = 'position:fixed;left:100px;top:100px;z-index:60;background:white;padding:20px';
    box.append(document.createTextNode(${JSON.stringify(text)}));
    for (const label of ${JSON.stringify(buttons)}) {
      const b = document.createElement('button'); b.textContent = label;
      b.onclick = () => { window.clicked.push(label);
        if (${JSON.stringify(closes)}.includes(label)) { dim.remove(); box.remove(); } };
      box.append(b);
    }
    document.body.append(dim, box);
  })()`);
}

const clicked = (page: EditorPage) => page.js<string[]>("window.clicked");

test("작성 중인 글 알림은 이어 쓰지 않는 쪽(취소)으로만 닫는다", async () => {
  const page = await tab();
  await popup(page, {
    cls: "se-popup-container",
    text: "작성 중인 글이 있습니다. 이어서 작성하시겠습니까?",
    buttons: ["취소", "확인"],
    closes: ["취소", "확인"],
  });
  expect(await requireClearScreen(page)).toBe("");
  expect(await clicked(page)).toEqual(["취소"]);
  expect(page.popup).toBeNull();
});

test("도움말 알림은 닫기 단추로 닫는다", async () => {
  const page = await tab();
  await popup(page, {
    cls: "se-help-panel-x",
    text: "도움말 synthetic-private-text",
    buttons: ["닫기"],
    closes: ["닫기"],
  });
  expect(await requireClearScreen(page)).toBe("");
  expect(await clicked(page)).toEqual(["닫기"]);
  expect(page.popup).toBeNull();
});

test("모르는 알림과 발행·저장 단추가 든 알림은 누르지 않고 종류만 남기고 멈춘다", async () => {
  for (const [cls, buttons] of [
    ["se-popup-container", ["확인", "닫기"]],
    ["se-help-panel-x", ["닫기", "발행"]],
  ] as const) {
    const page = await tab();
    await popup(page, {
      cls,
      text: "synthetic-private-notice",
      buttons: [...buttons],
      closes: [...buttons],
    });
    const start = browser.calls.length;
    expect(await requireClearScreen(page)).not.toBe("");
    expect(await clicked(page)).toEqual([]);
    expect(browser.calls.slice(start)).not.toContain(
      "Input.dispatchMouseEvent",
    );
    const error = page.fail("editor_failed", "화면을 덮은 알림이 있다");
    const diagnostics = error.extra.popup_diagnostics as Record<
      string,
      unknown
    >;
    expect(diagnostics.dismissed).toBe(false);
    expect(JSON.stringify(error.extra)).not.toContain("synthetic-private");
  }
});

test("눌렀는데도 알림이 남으면 dismissed 를 참으로 남기고 멈춘다", async () => {
  const page = await tab();
  await popup(page, {
    cls: "se-popup-container",
    text: "작성 중인 글이 있습니다. 이어서 작성하시겠습니까?",
    buttons: ["취소", "확인"],
    closes: [],
  });
  expect(await requireClearScreen(page)).not.toBe("");
  expect(await clicked(page)).toEqual(["취소"]);
  expect(page.popup).toMatchObject({ kind: "draft_notice", dismissed: true });
});

test("알림을 닫아 화면이 깨끗해지면 뒤의 실패에 지난 알림 진단을 붙이지 않는다", async () => {
  const page = await tab();
  await popup(page, {
    cls: "se-help-panel-x",
    text: "도움말",
    buttons: ["닫기"],
    closes: ["닫기"],
  });
  expect(await requireClearScreen(page)).toBe("");
  expect(page.popup).toBeNull();
  expect(page.fail("editor_failed", "다른 실패").extra).toEqual({});
});

test("단추는 검사한 첫 상자 안에서만 찾고 다른 상자의 같은 이름 단추는 누르지 않는다", async () => {
  const page = await tab();
  await popup(page, {
    cls: "se-help-panel-x",
    text: "도움말",
    buttons: ["확인"],
    closes: [],
  });
  await page.js(`(() => {
    const other = document.createElement('div');
    other.setAttribute('role', 'dialog');
    other.style.cssText = 'position:fixed;left:400px;top:300px;z-index:70;background:white';
    const b = document.createElement('button'); b.textContent = '닫기';
    b.onclick = () => window.clicked.push('other-닫기');
    other.append(b); document.body.append(other);
  })()`);
  await requireClearScreen(page);
  expect(await clicked(page)).toEqual(["확인"]);
});

test("아홉 번째 이후의 위험한 단추도 보고 아무것도 누르지 않는다", async () => {
  const page = await tab();
  await popup(page, {
    cls: "se-popup-container",
    text: "작성 중인 글이 있습니다. 이어서 작성하시겠습니까?",
    buttons: ["취소", ...Array(8).fill("계속"), "발행"],
    closes: ["취소"],
  });
  expect(await requireClearScreen(page)).not.toBe("");
  expect(await clicked(page)).toEqual([]);
});

test("알림 진단은 알려진 단추 이름과 상자 요소 이름만 남긴다", () => {
  const safe = filterPopupDiagnostics({
    kind: "nope",
    box: "div.se-popup-container extra",
    buttons: ["취소", "synthetic-private-label", ...Array(20).fill("확인")],
    dismissed: "yes",
    text: "synthetic-private-text",
  })!;
  expect(safe).toEqual({
    kind: "unknown",
    box: null,
    buttons: ["취소", "other", ...Array(6).fill("확인")],
    dismissed: false,
  });
  expect(filterPopupDiagnostics("x")).toBeUndefined();
});

/**
 * 제목에 첫 mousedown 이 올 때 합성 알림을 띄운다. fill 시작의 알림 확인은 이미 지난 뒤라,
 * 운영에서 난 「시작 확인 뒤, 제목을 누르는 도중에 뜬 알림」 을 재현한다. 네이버 alert 처럼 `se-popup-dim` 이 화면을 덮는다.
 */
function latePopup(
  page: EditorPage,
  buttons: string[],
  closes: string[],
  cls: string,
  text = "synthetic-private-notice",
) {
  return page.js(`document.getElementById('title').addEventListener('mousedown', () => {
    window.clicked = [];
    const dim = document.createElement('div');
    dim.className = 'se-popup-dim se-popup-dim-white'; dim.style.cssText = 'position:fixed;inset:0;z-index:50';
    const box = document.createElement('div');
    box.className = ${JSON.stringify(cls)}; box.setAttribute('role', 'dialog');
    box.style.cssText = 'position:fixed;inset:0;z-index:60;background:white;padding:20px';
    box.append(document.createTextNode(${JSON.stringify(text)}));
    for (const label of ${JSON.stringify(buttons)}) {
      const b = document.createElement('button'); b.textContent = label;
      b.onclick = () => { window.clicked.push(label);
        if (${JSON.stringify(closes)}.includes(label)) { dim.remove(); box.remove(); } };
      box.append(b);
    }
    document.body.append(dim, box);
  }, {once: true})`);
}

async function readyTab() {
  const { page, targetId } = await browser.tab({ stepSeconds: 0.6 });
  cleanups.push(async () => {
    page.close();
    await closeTab(browser.url, targetId);
  });
  expect(await open(page)).toBe("ready");
  page.stage = "fill";
  return page;
}

test("제목을 누르기 직전에 늦게 뜬 알려진 알림은 닫고 계속 입력한다", async () => {
  const page = await readyTab();
  await latePopup(
    page,
    ["취소", "확인"],
    ["취소", "확인"],
    "se-popup-container",
    "작성 중인 글이 있습니다. 이어서 작성하시겠습니까?",
  );
  const result = await focusField(page, TITLE_SELECTOR, ".se-documentTitle");
  expect(result.focused).toBe(true);
  expect(await clicked(page)).toEqual(["취소"]);
});

test("fill 도중 늦게 뜬 도움말 알림은 닫고 끝까지 넣는다", async () => {
  const page = await readyTab();
  await latePopup(page, ["닫기"], ["닫기"], "se-help-panel-x");
  await fill(page, "합성 제목", parseBody("합성 본문"), "synthetic-hash");
  expect(await clicked(page)).toEqual(["닫기"]);
  expect(page.popup).toBeNull();
});

test("모르는 alert 가 제목을 덮으면 누르지 않고 종류와 단추 이름을 남기고 멈춘다", async () => {
  const page = await readyTab();
  await latePopup(page, ["확인"], ["확인"], "se-popup-container");
  const start = browser.calls.length;
  const error = await fill(
    page,
    "합성 제목",
    parseBody("합성 본문"),
    "synthetic-hash",
  ).catch((e) => e);
  expect(error.code).toBe("editor_failed");
  expect(error.extra.popup_diagnostics).toMatchObject({
    kind: "unknown",
    buttons: ["확인"],
    dismissed: false,
  });
  expect(await clicked(page)).toEqual([]);
  expect(browser.calls.slice(start)).not.toContain("Input.insertText");
  expect(JSON.stringify(error.extra)).not.toContain("synthetic-private");
});
