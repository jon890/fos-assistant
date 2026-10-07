import { afterEach, expect, test } from "bun:test";
import { parseBody } from "../src/draft.ts";
import { closeTab, type EditorPage, openWriteTab } from "../src/editor/page.ts";
import { fill } from "../src/editor/text.ts";
import { EDITOR_METHODS, FakeCdp, FakeEditor } from "./fake-cdp.ts";

const TIMES = { stepSeconds: 0.1, photoSeconds: 0.3, saveSeconds: 0.3, openSeconds: 0.2 };
const cleanups: Array<() => Promise<void> | void> = [];

afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

async function editorTab() {
  const cdp = new FakeCdp({ allowed: EDITOR_METHODS });
  const editor = new FakeEditor();
  editor.install(cdp);
  cleanups.push(() => cdp.stop());
  const { targetId, page } = await openWriteTab(cdp.url, "example-blog", TIMES);
  cleanups.push(async () => {
    page.close();
    await closeTab(cdp.url, targetId);
  });
  return { cdp, editor, page: page as EditorPage };
}

/** 글자 입력과 Enter 를 받은 차례대로 편다. */
function typing(cdp: FakeCdp) {
  return cdp.calls.flatMap((call) => {
    if (call.method === "Input.insertText") return [call.params.text as string];
    if (
      call.method === "Input.dispatchKeyEvent" &&
      call.params.type === "rawKeyDown" &&
      call.params.key === "Enter"
    )
      return ["Enter"];
    return [];
  });
}

test("이모지가 든 줄은 글자와 이모지로 나눠 넣고 줄 사이에 Enter 를 누른다", async () => {
  const { cdp, editor, page } = await editorTab();

  await fill(page, "가상 제목", parseBody("맛있어요😋\n두 번째 줄"), "draft-hash");

  expect(typing(cdp)).toEqual(["가상 제목", "맛있어요", "😋", "Enter", "두 번째 줄"]);
  expect(editor.title).toBe("가상 제목");
  expect(editor.body).toEqual(["맛있어요😋", "두 번째 줄"]);
  expect(cdp.violations).toEqual([]);
});

test("구성요소 줄은 자리표시 글로, 빈 줄은 빈 문단으로 넣는다", async () => {
  const { editor, page } = await editorTab();
  const body = "[스티커: ogq_abc123-4]\n첫 줄\n\n[사진 1: 101.jpg]\n[지도: 가상국수 | 서울특별시 가상구 예시로 1]";

  await fill(page, "가상 제목", parseBody(body), "draft-hash");

  expect(editor.body).toEqual([
    "[스티커 자리: ogq_abc123-4]",
    "첫 줄",
    "",
    "[사진 자리: 101.jpg]",
    "[장소 자리: 가상국수 | 서울특별시 가상구 예시로 1]",
  ]);
});

test("화면에 들어가지 않은 줄이 있으면 editor_failed 로 멈춘다", async () => {
  const { editor, page } = await editorTab();
  editor.dropText = "두 번째 줄";
  page.stage = "fill";

  const failure = await fill(page, "가상 제목", parseBody("첫 줄\n두 번째 줄"), "draft-hash").catch(
    (error) => error,
  );

  expect(failure.code).toBe("editor_failed");
  expect(failure.stage).toBe("fill");
  expect(failure.message).not.toContain("두 번째 줄");
});
