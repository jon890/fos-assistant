import { afterAll, afterEach, beforeAll, expect, test } from "bun:test";
import { filterPopupDiagnostics } from "../src/editor/popup.ts";
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
  expect(page.popup).toMatchObject({ kind: "draft_notice", dismissed: true });
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
  expect(page.popup).toMatchObject({ kind: "help", buttons: ["닫기"] });
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
