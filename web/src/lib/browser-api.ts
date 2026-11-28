/**
 * 사용자 브라우저의 서버 라우트를 부른다. 응답을 읽고 실패를 문구로 바꾸는 일은 부르는 쪽이 한다.
 */

export type BrowserStatus =
  "STOPPED" | "STARTING" | "RUNNING" | "STOPPING" | "FAILED";

/** `GET /api/browser` 의 응답이다. 기능이 꺼져 있으면 `enabled` 만 온다. */
export type BrowserView = {
  enabled: boolean;
  exists?: boolean;
  status?: BrowserStatus;
  lastError?: string | null;
  startedAt?: string | null;
  lastActiveAt?: string | null;
  idleTimeoutSeconds?: number;
  /** 에이전트가 이 브라우저를 쓰게 하는 스위치다. 브라우저가 있을 때만 온다. */
  agentControl?: AgentControl;
};

/** 에이전트 조작 스위치다. `adminAllowed` 가 거짓이면 사용자가 켜 두었어도 쓰지 않는다. */
export type AgentControl = { enabled: boolean; adminAllowed: boolean };

/** 화면의 스위치 모양이다. 관리자가 꺼 두면 켜진 값과 상관없이 꺼진 모양으로 잠그고 안내를 보인다. 끄는 것은 늘 할 수 있다. */
export function agentControlSwitch(control: AgentControl): {
  checked: boolean;
  locked: boolean;
  note: string | null;
} {
  if (!control.adminAllowed)
    return {
      checked: false,
      locked: !control.enabled,
      note: "관리자가 에이전트의 브라우저 조작을 꺼 두었어요.",
    };
  return { checked: control.enabled, locked: false, note: null };
}

export type AdminBrowser = {
  id: number;
  userId: number;
  userName: string;
  status: BrowserStatus;
  lastError: string | null;
  startedAt: string | null;
  lastActiveAt: string | null;
};

export type BrowserAction = "create" | "start" | "stop" | "delete";

/** 상태마다 화면 문구와 배지 색이다. */
const BROWSER_STATUS: Record<
  BrowserStatus,
  { label: string; variant: "outline" | "info" | "success" | "destructive" }
> = {
  STOPPED: { label: "꺼져 있어요", variant: "outline" },
  STARTING: { label: "켜는 중이에요", variant: "info" },
  RUNNING: { label: "켜져 있어요", variant: "success" },
  STOPPING: { label: "끄는 중이에요", variant: "info" },
  FAILED: { label: "켜지 못했어요", variant: "destructive" },
};

/** 끄거나 지우다 proxy 호출이 실패했을 때 Control Plane 이 `lastError` 에 남기는 코드다. */
const STOP_FAILED = "stop_failed";

/** 끄다 실패한 `FAILED` 인가. 그 밖의 `FAILED` 는 켜다 실패한 것이다. */
export function failedToStop(
  status: BrowserStatus,
  lastError: string | null | undefined,
): boolean {
  return status === "FAILED" && lastError === STOP_FAILED;
}

/** 상태의 화면 문구와 배지 색이다. `FAILED` 는 `lastError` 로 켜기 실패와 끄기 실패를 나눈다. */
export function browserStatusView(
  status: BrowserStatus,
  lastError: string | null | undefined,
): { label: string; variant: "outline" | "info" | "success" | "destructive" } {
  return failedToStop(status, lastError)
    ? { label: "끄지 못했어요", variant: "destructive" }
    : BROWSER_STATUS[status];
}

const ACTIONS: Record<BrowserAction, { path: string; method: string }> = {
  create: { path: "/api/browser", method: "POST" },
  start: { path: "/api/browser/start", method: "POST" },
  stop: { path: "/api/browser/stop", method: "POST" },
  delete: { path: "/api/browser", method: "DELETE" },
};

export function fetchBrowser(): Promise<Response> {
  return fetch("/api/browser", { cache: "no-store" });
}

export function browserAction(action: BrowserAction): Promise<Response> {
  const { path, method } = ACTIONS[action];
  return fetch(path, { method });
}

export function fetchAdminBrowsers(): Promise<Response> {
  return fetch("/api/admin/browsers", { cache: "no-store" });
}

export function adminBrowserAction(
  id: number,
  action: "stop" | "delete",
): Promise<Response> {
  return action === "stop"
    ? fetch(`/api/admin/browsers/${id}/stop`, { method: "POST" })
    : fetch(`/api/admin/browsers/${id}`, { method: "DELETE" });
}

/** 로그인 화면 SSE 를 연다. 끝나지 않는 응답이라 `signal` 로 끊는다. 시작 주소는 고를 수 있다. */
export function openBrowserScreen(
  url: string | null,
  signal: AbortSignal,
): Promise<Response> {
  const query = url ? `?${new URLSearchParams({ url })}` : "";
  return fetch(`/api/browser/screen${query}`, { cache: "no-store", signal });
}

/** 열린 화면에 입력 하나를 보낸다. 본문 모양은 `components/browser/screen-input.ts` 가 정한다. */
export function sendScreenInput(body: object): Promise<Response> {
  return fetch("/api/browser/screen/input", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

/** 「직접 할게요」 다. 대화 패널에서 누르면 그 대화의 공개 번호를 싣는다. */
export function takeOverBrowser(
  conversationId: string | null,
): Promise<Response> {
  return fetch("/api/browser/takeover", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(conversationId ? { conversationId } : {}),
  });
}

/** 「돌려주기」 다. 잠금만 푼다. 글을 보낼지는 부르는 쪽이 정한다. */
export function giveBackBrowser(): Promise<Response> {
  return fetch("/api/browser/give-back", { method: "POST" });
}

/** 에이전트가 내 브라우저를 쓰게 할지 바꾼다. 성공하면 바뀐 `BrowserView` 가 온다. */
export function setAgentControl(enabled: boolean): Promise<Response> {
  return fetch("/api/browser/agent-control", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ enabled }),
  });
}

/** 관리자. 그룹 전체의 에이전트 브라우저 조작 스위치다. 응답은 `{ enabled }` 다. */
export function fetchAdminAgentControl(): Promise<Response> {
  return fetch("/api/admin/browsers/agent-control", { cache: "no-store" });
}

/** 관리자. 그룹 전체 스위치를 바꾼다. 끄면 켜 둔 사용자도 다음 호출부터 쓰지 못한다. */
export function setAdminAgentControl(enabled: boolean): Promise<Response> {
  return fetch("/api/admin/browsers/agent-control", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ enabled }),
  });
}
