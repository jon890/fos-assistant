import { createHash } from "node:crypto";
import { expect, test } from "./fixtures.ts";
import { openAccountMenu } from "./helpers.ts";
import type { Page, Route } from "../../node_modules/@playwright/test/index.js";

// 설정 「알림」 화면의 이 기기 상태와 등록을 본다(docs/features/attention/spec.md 의 REQ1-REQ3, REQ9).
// 브라우저의 알림 허락과 서비스 워커, 푸시 구독은 init script 로 대역하고 `/api/push/*` 는 route 로 대역한다.
// 실제 구독은 푸시 서비스에 닿아야 해서 검사 브라우저에서 만들 수 없다.

const PUBLIC_KEY = "BAECAwQ"; // base64url 바이트 [4, 1, 2, 3, 4]
const ROTATED_KEY = "BAUGBwg"; // base64url 바이트 [4, 5, 6, 7, 8]
const ENDPOINT = "https://fcm.googleapis.com/fcm/send/browser-test";
const MAC_CHROME_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
const IPHONE_UA =
  "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1";
const OLD_IPHONE_UA =
  "Mozilla/5.0 (iPhone; CPU iPhone OS 16_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.3 Mobile/15E148 Safari/604.1";

// 검사 브라우저의 user agent 는 `HeadlessChrome` 이라 실제 이름과 다르다. PC Chrome 으로 고정하고 아이폰 사례만 바꾼다.
test.use({ userAgent: MAC_CHROME_UA });

function hashOf(endpoint: string): string {
  return createHash("sha256").update(endpoint).digest("hex");
}

type BrowserSetup = {
  permission: "default" | "granted" | "denied";
  /** 허락을 물으면 돌려줄 답이다 */
  answer: "granted" | "denied" | "default";
  supported: boolean;
  standalone: boolean;
  /** 화면을 열 때 이미 있는 구독의 공개 키다. 없으면 구독이 없다 */
  existingKey: number[] | null;
};

type Recorded = { subscribed: number; unsubscribed: number; registered: number };

/**
 * 알림 허락, 서비스 워커, 푸시 구독을 대역한다. 부른 횟수는 `window.__push` 에 남긴다.
 *
 * <p>함수는 브라우저에서 돈다. 바깥 변수를 쓰지 않고 인자로만 받는다.
 */
async function fakeBrowser(page: Page, setup: Partial<BrowserSetup> = {}) {
  const options: BrowserSetup = {
    permission: "default",
    answer: "granted",
    supported: true,
    standalone: false,
    existingKey: null,
    ...setup,
  };
  await page.addInitScript(
    ({ options, endpoint }) => {
      const record = { subscribed: 0, unsubscribed: 0, registered: 0 };
      (window as unknown as { __push: typeof record }).__push = record;
      const realMatchMedia = window.matchMedia.bind(window);
      window.matchMedia = (query: string) =>
        query === "(display-mode: standalone)"
          ? ({ matches: options.standalone, media: query } as MediaQueryList)
          : realMatchMedia(query);
      if (!options.supported) {
        delete (window as { PushManager?: unknown }).PushManager;
        return;
      }
      let permission = options.permission;
      class FakeNotification {
        static get permission() {
          return permission;
        }
        static async requestPermission() {
          permission = options.answer;
          return permission;
        }
      }
      Object.defineProperty(window, "Notification", { value: FakeNotification, configurable: true });
      Object.defineProperty(window, "PushManager", { value: class {}, configurable: true });

      // 화면을 다시 열어도 같은 브라우저처럼 구독이 남게 대역 상태를 sessionStorage 에 둔다.
      const STORE = "__push_fake";
      const stored = JSON.parse(sessionStorage.getItem(STORE) ?? "null") as
        | { key: number[] | null; registered: boolean }
        | null;
      const saved = stored ?? { key: options.existingKey, registered: options.existingKey !== null };
      const save = () => sessionStorage.setItem(STORE, JSON.stringify(saved));
      let subscription: unknown = null;
      const makeSubscription = (key: number[]) => {
        const made = {
          endpoint,
          options: { applicationServerKey: new Uint8Array(key).buffer },
          toJSON: () => ({ endpoint, keys: { p256dh: "p256dh-key", auth: "auth-key" } }),
          unsubscribe: async () => {
            record.unsubscribed += 1;
            if (subscription === made) {
              subscription = null;
              saved.key = null;
              save();
            }
            return true;
          },
        };
        return made;
      };
      if (saved.key) subscription = makeSubscription(saved.key);
      let registered = saved.registered;
      const registration = {
        pushManager: {
          getSubscription: async () => subscription,
          subscribe: async (init: { applicationServerKey: Uint8Array }) => {
            record.subscribed += 1;
            saved.key = Array.from(init.applicationServerKey);
            save();
            subscription = makeSubscription(saved.key);
            return subscription;
          },
        },
      };
      Object.defineProperty(navigator, "serviceWorker", {
        configurable: true,
        value: {
          getRegistration: async () => (registered ? registration : undefined),
          register: async () => {
            record.registered += 1;
            registered = true;
            saved.registered = true;
            save();
            return registration;
          },
          get ready() {
            return Promise.resolve(registration);
          },
        },
      });
    },
    { options, endpoint: ENDPOINT },
  );
}

type Device = { id: string; name: string; registeredAt: string; endpointHash: string };

/** 푸시 경로를 대역한다. PUT 은 받은 본문을 남기고 `putStatuses` 차례로 답한다. */
async function routePush(
  page: Page,
  state: {
    config?: { enabled: boolean; publicKey: string | null };
    devices: Device[];
    putStatuses?: { status: number; code?: string; message?: string }[];
  },
) {
  const puts: Record<string, unknown>[] = [];
  const deletes: Record<string, unknown>[] = [];
  let config = state.config ?? { enabled: true, publicKey: PUBLIC_KEY };
  await page.route("**/api/push/config", (route: Route) => route.fulfill({ json: config }));
  await page.route("**/api/push/devices", async (route: Route) => {
    if (route.request().method() === "GET") return route.fulfill({ json: state.devices });
    const body = route.request().postDataJSON() as Record<string, unknown>;
    puts.push(body);
    const answer = state.putStatuses?.shift();
    if (answer && answer.status !== 200) {
      // 서버가 키를 바꿨다는 거절 뒤에는 새 공개 키를 준다.
      if (answer.code === "PUSH_KEY_CHANGED") config = { enabled: true, publicKey: ROTATED_KEY };
      return route.fulfill({ status: answer.status, json: { code: answer.code, message: answer.message ?? "거절" } });
    }
    const device = {
      id: `device-${puts.length}`,
      name: String(body.name),
      registeredAt: "2026-11-20T03:00:00Z",
      endpointHash: hashOf(String(body.endpoint)),
    };
    state.devices = [device, ...state.devices.filter((d) => d.endpointHash !== device.endpointHash)];
    return route.fulfill({ json: device });
  });
  await page.route("**/api/push/devices/current", async (route: Route) => {
    const body = route.request().postDataJSON() as Record<string, unknown>;
    deletes.push(body);
    state.devices = state.devices.filter((d) => d.endpointHash !== hashOf(String(body.endpoint)));
    return route.fulfill({ status: 204, body: "" });
  });
  return { puts, deletes };
}

async function recorded(page: Page): Promise<Recorded> {
  return page.evaluate(() => (window as unknown as { __push: Recorded }).__push);
}

function thisDevice(page: Page) {
  return page.getByRole("region", { name: "이 기기" });
}

function deviceList(page: Page) {
  return page.getByRole("region", { name: "알림을 받는 기기" });
}

test("단추를 누르고 허락하면 이 기기가 목록에 한 줄로 더해지고 시간대와 이름이 함께 간다", async ({ page }) => {
  await fakeBrowser(page);
  const calls = await routePush(page, { devices: [] });
  await page.goto("/settings/notifications");
  await expect(page.getByRole("heading", { name: "알림", level: 1 })).toBeVisible();
  await expect(deviceList(page).getByText("알림을 받는 기기가 없어요.")).toBeVisible();

  await thisDevice(page).getByRole("button", { name: "이 기기에서 알림 받기" }).click();
  await expect(thisDevice(page).getByText("이 기기는 알림을 받고 있어요")).toBeVisible();
  await expect(thisDevice(page).getByRole("button", { name: "이 기기에서 알림 받기" })).toHaveCount(0);
  const rows = deviceList(page).getByTestId("push-device");
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toContainText("이 기기");

  expect(calls.puts).toHaveLength(1);
  expect(calls.puts[0]).toMatchObject({
    endpoint: ENDPOINT,
    keys: { p256dh: "p256dh-key", auth: "auth-key" },
    timeZone: "Asia/Seoul",
    publicKey: PUBLIC_KEY,
  });
  expect(calls.puts[0].name).toBe("Mac Chrome");
  expect((await recorded(page)).registered).toBe(1);

  // 다시 열어도 같은 기기가 두 줄이 되지 않고 등록을 다시 보내지 않는다.
  await page.reload();
  await expect(thisDevice(page).getByText("이 기기는 알림을 받고 있어요")).toBeVisible();
  await expect(deviceList(page).getByTestId("push-device")).toHaveCount(1);
  expect(calls.puts).toHaveLength(1);
});

test("서버에서 지운 기기는 브라우저에 구독이 남아도 다시 등록하지 않고 단추를 보인다", async ({ page }) => {
  await fakeBrowser(page, { permission: "granted", existingKey: [4, 1, 2, 3, 4] });
  const other: Device = { id: "other", name: "iPhone 앱", registeredAt: "2026-11-19T03:00:00Z", endpointHash: hashOf("https://web.push.apple.com/x") };
  const calls = await routePush(page, { devices: [other] });
  await page.goto("/settings/notifications");
  await expect(thisDevice(page).getByRole("button", { name: "이 기기에서 알림 받기" })).toBeVisible();
  const rows = deviceList(page).getByTestId("push-device");
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toContainText("iPhone 앱");
  await expect(rows.first()).not.toContainText("이 기기");
  expect(calls.puts).toHaveLength(0);
});

test("이 기기에서 그만 받으면 서버 목록에서 빼고 구독을 끊는다", async ({ page }) => {
  await fakeBrowser(page, { permission: "granted", existingKey: [4, 1, 2, 3, 4] });
  const mine: Device = { id: "mine", name: "Mac Chrome", registeredAt: "2026-11-19T03:00:00Z", endpointHash: hashOf(ENDPOINT) };
  const calls = await routePush(page, { devices: [mine] });
  await page.goto("/settings/notifications");
  await thisDevice(page).getByRole("button", { name: "이 기기에서 그만 받기" }).click();
  await expect(thisDevice(page).getByRole("button", { name: "이 기기에서 알림 받기" })).toBeVisible();
  await expect(deviceList(page).getByTestId("push-device")).toHaveCount(0);
  expect(calls.deletes).toEqual([{ endpoint: ENDPOINT }]);
  expect((await recorded(page)).unsubscribed).toBe(1);
});

test("서버의 공개 키가 바뀌었다고 거절되면 다시 구독해 한 번 더 등록한다", async ({ page }) => {
  await fakeBrowser(page);
  const calls = await routePush(page, { devices: [], putStatuses: [{ status: 409, code: "PUSH_KEY_CHANGED" }] });
  await page.goto("/settings/notifications");
  await thisDevice(page).getByRole("button", { name: "이 기기에서 알림 받기" }).click();
  await expect(thisDevice(page).getByText("이 기기는 알림을 받고 있어요")).toBeVisible();
  expect(calls.puts.map((body) => body.publicKey)).toEqual([PUBLIC_KEY, ROTATED_KEY]);
  const record = await recorded(page);
  expect(record.subscribed).toBe(2);
  expect(record.unsubscribed).toBe(1);
});

test("서버가 알림 주소를 거절하면 한국어로 다시 시도할 방법을 보인다", async ({ page }) => {
  await fakeBrowser(page);
  const calls = await routePush(page, {
    devices: [],
    putStatuses: [{
      status: 400,
      code: "VALIDATION_FAILED",
      message: "endpoint is not an allowed push service",
    }],
  });
  await page.goto("/settings/notifications");
  const enable = thisDevice(page).getByRole("button", { name: "이 기기에서 알림 받기" });
  await enable.click();
  await expect(thisDevice(page).getByRole("alert")).toHaveText(
    "이 기기를 등록하지 못했어요. 다른 브라우저에서 다시 시도하거나 관리자에게 알려 주세요.",
  );
  await expect(enable).toBeEnabled();
  await expect(deviceList(page).getByTestId("push-device")).toHaveCount(0);
  expect(calls.puts).toHaveLength(1);

  // 다시 누르면 등록을 한 번 더 보내고, 이번에 받아들여지면 목록에 보인다.
  await enable.click();
  await expect(deviceList(page).getByTestId("push-device")).toHaveCount(1);
  expect(calls.puts).toHaveLength(2);
});

test("알림을 거절한 브라우저는 단추 없이 다시 켜는 방법을 보인다", async ({ page }) => {
  await fakeBrowser(page, { permission: "denied" });
  await routePush(page, { devices: [] });
  await page.goto("/settings/notifications");
  await expect(thisDevice(page).getByText(/알림을 막아 두었어요/)).toBeVisible();
  await expect(page.getByRole("button", { name: "이 기기에서 알림 받기" })).toHaveCount(0);
});

test("허락 창에서 거절하면 등록하지 않고 거절 안내로 바뀐다", async ({ page }) => {
  await fakeBrowser(page, { answer: "denied" });
  const calls = await routePush(page, { devices: [] });
  await page.goto("/settings/notifications");
  await thisDevice(page).getByRole("button", { name: "이 기기에서 알림 받기" }).click();
  await expect(thisDevice(page).getByText(/알림을 막아 두었어요/)).toBeVisible();
  expect(calls.puts).toHaveLength(0);
});

test("웹 푸시를 지원하지 않는 브라우저는 까닭을 보인다", async ({ page }) => {
  await fakeBrowser(page, { supported: false });
  await routePush(page, { devices: [] });
  await page.goto("/settings/notifications");
  await expect(thisDevice(page).getByText(/이 브라우저는 알림을 받을 수 없어요/)).toBeVisible();
  await expect(page.getByRole("button", { name: "이 기기에서 알림 받기" })).toHaveCount(0);
});

test("서버에 키가 없으면 아직 보낼 수 없다고 보인다", async ({ page }) => {
  await fakeBrowser(page);
  await routePush(page, { config: { enabled: false, publicKey: null }, devices: [] });
  await page.goto("/settings/notifications");
  await expect(thisDevice(page).getByText("이 서버는 아직 알림을 보낼 수 없어요.")).toBeVisible();
  await expect(page.getByRole("button", { name: "이 기기에서 알림 받기" })).toHaveCount(0);
});

test.describe("로그아웃", () => {
  test("로그아웃하면 이 브라우저의 기기를 빼고 구독을 끊은 뒤 로그인 화면으로 간다", async ({ page }, testInfo) => {
    await fakeBrowser(page, { permission: "granted", existingKey: [4, 1, 2, 3, 4] });
    const mine: Device = { id: "mine", name: "Mac Chrome", registeredAt: "2026-11-19T03:00:00Z", endpointHash: hashOf(ENDPOINT) };
    const calls = await routePush(page, { devices: [mine] });
    await page.goto("/new");
    await openAccountMenu(page, testInfo);
    await page.getByRole("menuitem", { name: "로그아웃" }).click();
    await expect(page).toHaveURL(/\/signin$/);
    expect(calls.deletes).toEqual([{ endpoint: ENDPOINT }]);
    // 같은 탭의 sessionStorage 에 대역 구독 상태가 남는다. 구독을 끊었으면 키가 비어 있다.
    const stored = await page.evaluate(() => JSON.parse(sessionStorage.getItem("__push_fake") ?? "null"));
    expect(stored).toMatchObject({ key: null });
  });

  test("기기 빼기가 멈춰도 2초 뒤 로그아웃한다", async ({ page }, testInfo) => {
    await fakeBrowser(page, { permission: "granted", existingKey: [4, 1, 2, 3, 4] });
    let reached = false;
    await page.route("**/api/push/devices/current", async () => {
      reached = true;
      await new Promise(() => {});
    });
    await page.goto("/new");
    await openAccountMenu(page, testInfo);
    const started = Date.now();
    await page.getByRole("menuitem", { name: "로그아웃" }).click();
    await expect(page).toHaveURL(/\/signin$/, { timeout: 10_000 });
    expect(reached).toBe(true);
    expect(Date.now() - started).toBeGreaterThanOrEqual(1_900);
  });
});

test.describe("아이폰", () => {
  test.use({ userAgent: IPHONE_UA });

  test("Safari 탭으로 열면 홈 화면에 추가하는 안내 셋을 보인다", async ({ page }) => {
    // 아이폰 Safari 탭에는 웹 푸시 API 가 없다.
    await fakeBrowser(page, { supported: false });
    await routePush(page, { devices: [] });
    await page.goto("/settings/notifications");
    const steps = thisDevice(page).getByTestId("ios-install-step");
    await expect(steps).toHaveCount(3);
    await expect(steps).toContainText(["공유", "홈 화면에 추가", "앱에서 다시 열기"]);
    await expect(page.getByRole("button", { name: "이 기기에서 알림 받기" })).toHaveCount(0);
  });

  test("홈 화면 앱에서 알림을 막았으면 기기 설정에서 다시 켜는 방법을 보인다", async ({ page }) => {
    await fakeBrowser(page, { standalone: true, permission: "denied" });
    await routePush(page, { devices: [] });
    await page.goto("/settings/notifications");
    await expect(thisDevice(page).getByText("이 앱의 알림을 막아 두었어요. 기기의 「설정 → 알림 → 검사용 비서」 에서 알림을 허용한 뒤 이 화면을 다시 열어 주세요.")).toBeVisible();
    await expect(thisDevice(page).getByText(/주소창/)).toHaveCount(0);
    await expect(page.getByRole("button", { name: "이 기기에서 알림 받기" })).toHaveCount(0);
  });

  test("홈 화면 앱으로 열면 단추를 보이고 이름에 「앱」 이 붙는다", async ({ page }) => {
    await fakeBrowser(page, { standalone: true });
    const calls = await routePush(page, { devices: [] });
    await page.goto("/settings/notifications");
    await expect(thisDevice(page).getByTestId("ios-install-step")).toHaveCount(0);
    await thisDevice(page).getByRole("button", { name: "이 기기에서 알림 받기" }).click();
    await expect(deviceList(page).getByTestId("push-device")).toHaveCount(1);
    expect(calls.puts[0].name).toBe("iPhone 앱");
  });
});

test.describe("iOS 16.4 미만", () => {
  test.use({ userAgent: OLD_IPHONE_UA });

  test("버전 안내를 보인다", async ({ page }) => {
    await fakeBrowser(page, { standalone: true });
    await routePush(page, { devices: [] });
    await page.goto("/settings/notifications");
    await expect(thisDevice(page).getByText(/iOS 16.4 이상에서 받을 수 있어요/)).toBeVisible();
    await expect(page.getByRole("button", { name: "이 기기에서 알림 받기" })).toHaveCount(0);
  });
});
