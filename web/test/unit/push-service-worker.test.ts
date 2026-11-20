import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

// 서비스 워커의 글 해석과 창 고르기를 본다(docs/features/attention/spec.md 의 REQ5, REQ6).

type Payload = { title: string; body: string; url: string; tag?: string };
type FakeClient = { url: string; visibilityState: string };
type Worker = {
  parsePushPayload(text: string): Payload;
  pickClient(clients: FakeClient[], origin: string): FakeClient | null;
};

async function loadWorker(): Promise<Worker> {
  const source = await readFile(new URL("../../public/sw.js", import.meta.url), "utf8");
  // 이벤트를 붙이지 않게 `self` 없이 돌린다. 최상위 함수는 context 에 남는다.
  const context = vm.createContext({ URL });
  vm.runInContext(source, context);
  return context as unknown as Worker;
}

test("푸시 글의 제목과 본문을 그대로 띄우고 여는 주소와 tag 를 남긴다", async () => {
  const worker = await loadWorker();
  const payload = worker.parsePushPayload(
    JSON.stringify({ title: "「아침 뉴스」 실행을 마쳤어요", body: "결과를 확인해 보세요", url: "/n/abc", tag: "abc" }),
  );
  assert.equal(payload.title, "「아침 뉴스」 실행을 마쳤어요");
  assert.equal(payload.body, "결과를 확인해 보세요");
  assert.equal(payload.url, "/n/abc");
  assert.equal(payload.tag, "abc");
});

test("읽지 못한 글은 알림 목록을 여는 일반 글로 띄운다", async () => {
  const worker = await loadWorker();
  for (const text of ["", "not json", "null", JSON.stringify({ body: "제목 없음" })]) {
    const payload = worker.parsePushPayload(text);
    assert.equal(payload.title, "새 알림이 있어요", text);
    assert.equal(payload.url, "/notifications", text);
  }
});

test("같은 사이트 경로가 아닌 여는 주소는 알림 목록으로 바꾼다", async () => {
  const worker = await loadWorker();
  for (const url of ["https://evil.example/", "//evil.example/", "/\\evil.example", "javascript:alert(1)", 3]) {
    assert.equal(worker.parsePushPayload(JSON.stringify({ title: "t", url })).url, "/notifications", String(url));
  }
});

test("열린 창 가운데 같은 출처의 보이는 창을 먼저 고른다", async () => {
  const worker = await loadWorker();
  const hidden = { url: "https://app.example/chat/1", visibilityState: "hidden" };
  const visible = { url: "https://app.example/now", visibilityState: "visible" };
  const other = { url: "https://other.example/", visibilityState: "visible" };
  assert.equal(worker.pickClient([hidden, other, visible], "https://app.example"), visible);
  assert.equal(worker.pickClient([other, hidden], "https://app.example"), hidden);
  assert.equal(worker.pickClient([other], "https://app.example"), null);
  assert.equal(worker.pickClient([], "https://app.example"), null);
});

type Listener = (event: unknown) => void;
type Shown = { title: string; options: { body: string; tag?: string; data: { url: string } } };
type Window = { url: string; visibilityState: string; focused: number; navigated: string[]; navigate(url: string): Promise<unknown> };

/** 가짜 `self` 를 두고 서비스 워커를 읽는다. 붙인 사건 처리기와 표시, 창 열기 기록을 돌려준다. */
async function loadWithSelf(windows: Window[]) {
  const listeners = new Map<string, Listener>();
  const shown: Shown[] = [];
  const opened: string[] = [];
  const self = {
    location: { origin: "https://app.example" },
    addEventListener(type: string, listener: Listener) {
      listeners.set(type, listener);
    },
    skipWaiting() {},
    registration: {
      async showNotification(title: string, options: Shown["options"]) {
        shown.push({ title, options });
      },
    },
    clients: {
      async claim() {},
      async matchAll() {
        return windows;
      },
      async openWindow(url: string) {
        opened.push(url);
      },
    },
  };
  const source = await readFile(new URL("../../public/sw.js", import.meta.url), "utf8");
  vm.runInContext(source, vm.createContext({ URL, self }));
  return { listeners, shown, opened };
}

/** `waitUntil` 에 넘긴 일을 기다리는 사건을 보낸다. */
async function dispatch(listener: Listener | undefined, event: Record<string, unknown>) {
  assert.ok(listener, "처리기가 붙지 않았다");
  let pending: Promise<unknown> = Promise.resolve();
  listener({ ...event, waitUntil: (work: Promise<unknown>) => (pending = work) });
  await pending;
}

function fakeWindow(url: string, visibilityState: string, navigateResult: "ok" | "fail" = "ok"): Window {
  const window: Window = {
    url,
    visibilityState,
    focused: 0,
    navigated: [],
    async navigate(target: string) {
      window.navigated.push(target);
      if (navigateResult === "fail") throw new Error("navigate 실패");
      return window;
    },
  };
  Object.assign(window, { focus: async () => (window.focused += 1) });
  return window;
}

test("푸시를 받으면 알림의 제목과 본문으로 시스템 알림을 띄운다", async () => {
  const worker = await loadWithSelf([]);
  const text = JSON.stringify({ title: "승인을 기다리는 요청이 있어요", body: "메모 쓰기", url: "/n/abc", tag: "abc" });
  await dispatch(worker.listeners.get("push"), { data: { text: () => text } });
  // vm 안에서 만든 객체라 prototype 이 달라 JSON 으로 옮겨 비교한다.
  assert.deepEqual(JSON.parse(JSON.stringify(worker.shown)), [
    { title: "승인을 기다리는 요청이 있어요", options: { body: "메모 쓰기", tag: "abc", data: { url: "/n/abc" } } },
  ]);
});

function clickEvent(url: string) {
  let closed = 0;
  return {
    event: { notification: { data: { url }, close: () => (closed += 1) } },
    closed: () => closed,
  };
}

test("알림을 누르면 열린 창을 앞으로 가져와 여는 주소로 옮긴다", async () => {
  const open = fakeWindow("https://app.example/now", "visible");
  const worker = await loadWithSelf([open]);
  const click = clickEvent("/n/abc");
  await dispatch(worker.listeners.get("notificationclick"), click.event);
  assert.equal(click.closed(), 1);
  assert.equal(open.focused, 1);
  assert.deepEqual(open.navigated, ["/n/abc"]);
  assert.deepEqual(worker.opened, []);
});

test("열린 창이 없거나 옮기지 못하면 새 창으로 연다", async () => {
  const none = await loadWithSelf([]);
  await dispatch(none.listeners.get("notificationclick"), clickEvent("/n/abc").event);
  assert.deepEqual(none.opened, ["/n/abc"]);

  const stuck = fakeWindow("https://app.example/now", "visible", "fail");
  const failed = await loadWithSelf([stuck]);
  await dispatch(failed.listeners.get("notificationclick"), clickEvent("/n/abc").event);
  assert.deepEqual(stuck.navigated, ["/n/abc"]);
  assert.deepEqual(failed.opened, ["/n/abc"]);
});
