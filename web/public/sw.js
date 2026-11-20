// 웹 푸시를 받아 시스템 알림으로 띄우고, 누르면 창을 여는 서비스 워커다(docs/features/attention/design.md 의 「웹」).
// 번들러를 거치지 않는 파일이다. 판단 함수는 아래 두 개뿐이고 `web/test/unit/push-service-worker.test.ts` 가 이 파일을 읽어 부른다.
// 캐시는 두지 않는다. 화면을 오프라인으로 보이는 일은 이 기능의 범위가 아니다.

/** 글을 읽지 못했을 때 여는 곳이다. 알림 목록이다. */
const FALLBACK_URL = "/notifications";

/** 같은 사이트 경로만 받는다. `//` 로 시작하면 다른 출처로 가는 주소라 받지 않는다. */
function sameSitePath(value) {
  if (typeof value !== "string") return null;
  if (!value.startsWith("/") || value.startsWith("//") || value.includes("\\"))
    return null;
  // 제어 문자가 섞이면 브라우저가 지우고 해석해 `//` 처럼 읽힐 수 있다. 로그인 화면의 돌아갈 주소 검사와 같은 규칙이다.
  for (const char of value) {
    const code = char.charCodeAt(0);
    if (code < 0x20 || code === 0x7f) return null;
  }
  return value;
}

/**
 * 푸시 글 `{"title","body","url","tag"}` 을 읽는다. 백엔드가 알림의 제목과 본문을 그대로 넣는다(스펙 DECISION4).
 * 읽지 못하면 알림 목록을 여는 일반 글로 띄운다. 받은 푸시는 반드시 알림으로 띄워야 하는 브라우저가 있다.
 */
function parsePushPayload(text) {
  let data = null;
  try {
    data = JSON.parse(text);
  } catch {
    data = null;
  }
  if (
    !data ||
    typeof data !== "object" ||
    typeof data.title !== "string" ||
    !data.title
  ) {
    return {
      title: "새 알림이 있어요",
      body: "",
      url: FALLBACK_URL,
      tag: undefined,
    };
  }
  return {
    title: data.title,
    body: typeof data.body === "string" ? data.body : "",
    url: sameSitePath(data.url) ?? FALLBACK_URL,
    tag: typeof data.tag === "string" && data.tag ? data.tag : undefined,
  };
}

/** 누른 알림을 옮겨 보일 창을 고른다. 같은 출처의 창 가운데 보이는 창을 먼저, 없으면 첫 창이다. */
function pickClient(clients, origin) {
  const own = clients.filter((client) => {
    try {
      return new URL(client.url).origin === origin;
    } catch {
      return false;
    }
  });
  return (
    own.find((client) => client.visibilityState === "visible") ?? own[0] ?? null
  );
}

if (
  typeof self !== "undefined" &&
  typeof self.addEventListener === "function"
) {
  self.addEventListener("install", () => {
    self.skipWaiting();
  });

  self.addEventListener("activate", (event) => {
    event.waitUntil(self.clients.claim());
  });

  // 앱을 보고 있어도 늘 시스템 알림을 띄운다(스펙 DECISION19).
  self.addEventListener("push", (event) => {
    const payload = parsePushPayload(event.data ? event.data.text() : "");
    event.waitUntil(
      self.registration.showNotification(payload.title, {
        body: payload.body,
        tag: payload.tag,
        data: { url: payload.url },
      }),
    );
  });

  self.addEventListener("notificationclick", (event) => {
    event.notification.close();
    const url =
      sameSitePath(event.notification.data && event.notification.data.url) ??
      FALLBACK_URL;
    event.waitUntil(
      (async () => {
        const windows = await self.clients.matchAll({
          type: "window",
          includeUncontrolled: true,
        });
        const client = pickClient(windows, self.location.origin);
        if (client) {
          await client.focus();
          // 다른 출처의 화면이 아니면 옮길 수 있다. 실패하면 새 창을 연다.
          if (typeof client.navigate === "function") {
            const moved = await client.navigate(url).catch(() => null);
            if (moved) return;
          }
        }
        await self.clients.openWindow(url);
      })(),
    );
  });
}
