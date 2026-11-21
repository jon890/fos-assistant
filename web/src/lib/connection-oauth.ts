import {
  CONNECTOR_ID_PATTERN,
  FIELD_KEY_PATTERN,
  connectionCall,
  connectionErrorMessage,
  connectorLinkHref,
} from "./connection.ts";

/**
 * 단추 연결(OAuth) 선언이다. `ready` 면 관리자가 앱을 설정해 단추를 쓸 수 있다.
 * `fields` 는 단추가 대신 채우는 비밀 칸의 key 이고 「직접 입력」 아래 접어 둔다.
 */
export type ConnectorOAuth = {
  provider: string;
  ready: boolean;
  fields: string[];
  /** 사용자가 서비스에서 허락을 직접 거둘 화면이다. */
  settingsUrl: string | null;
  /** 앱 설치와 저장소를 바꾸는 화면이다. 설치가 따로 있는 서비스(GitHub App)만 있다. */
  installSettingsUrl: string | null;
};

/** 연결 방식이다. 옛 Control Plane 은 내지 않고 직접 입력으로 읽는다. */
export type ConnectionAuth = {
  method: "oauth" | "manual";
  provider: string | null;
  reconnectRequired: boolean;
};

/** 단추 연결을 해제할 때 서비스 쪽 허락을 취소한 결과다. 해제 응답에만 있다. */
export type ConnectionOAuthRevoke = {
  status: "revoked" | "failed";
  settingsUrl: string | null;
  installSettingsUrl: string | null;
};

export const OAUTH_OUTCOMES = [
  "CONNECTED",
  "REAUTHORIZE",
  "DENIED",
  "INVALID_STATE",
  "SCOPE_DENIED",
  "APP_REJECTED",
  "VERIFY_FAILED",
  "SUPERSEDED",
  "UNAVAILABLE",
] as const;

export type OAuthOutcome = (typeof OAUTH_OUTCOMES)[number];

/** 서비스 화면에서 돌아온 결과다. `connectorId` 는 상태 값을 믿을 수 없으면 null 이다. */
export type OAuthCompletion = {
  outcome: OAuthOutcome;
  connectorId: string | null;
  /** `REAUTHORIZE` 일 때 한 번 더 보낼 로그인 주소다. */
  authorizeUrl: string | null;
  /** `VERIFY_FAILED` 일 때 확인 도구의 오류 코드다. */
  reason: string | null;
};

/** 돌아온 결과를 연결 화면에 넘길 때 쓰는 주소 검색어다. */
export const OAUTH_RESULT_PARAM = "oauth";
export const OAUTH_REASON_PARAM = "reason";

const PROVIDER_NAMES: Record<string, string> = {
  github: "GitHub",
  google: "Google",
};

const PROVIDER_PATTERN = /^[a-z][a-z0-9-]{0,31}$/;
const REASON_PATTERN = /^[A-Z][A-Z0-9_]{0,63}$/;

/** 화면에 보일 서비스 이름이다. 모르는 제공자는 이름을 그대로 쓴다. */
export function providerName(provider: string | null | undefined): string {
  if (!provider) return "서비스";
  return Object.hasOwn(PROVIDER_NAMES, provider)
    ? PROVIDER_NAMES[provider]
    : provider;
}

export function isOAuthOutcome(value: unknown): value is OAuthOutcome {
  return (
    typeof value === "string" &&
    (OAUTH_OUTCOMES as readonly string[]).includes(value)
  );
}

export function isOAuthReason(value: unknown): value is string {
  return typeof value === "string" && REASON_PATTERN.test(value);
}

/** 돌아온 결과를 연결 화면에 보일 까닭으로 바꾼다. 성공은 null 이다. */
export function oauthOutcomeMessage(
  outcome: OAuthOutcome,
  reason: string | null,
): string | null {
  switch (outcome) {
    case "CONNECTED":
    case "REAUTHORIZE":
      return null;
    case "DENIED":
      return "연결하지 않았어요. 서비스 화면에서 허락하지 않았거나 창을 닫았어요.";
    case "INVALID_STATE":
      return "연결 요청이 만료됐거나 이 계정에서 시작한 요청이 아니에요.";
    case "SCOPE_DENIED":
      return "요청한 권한을 모두 허락해야 연결돼요.";
    case "APP_REJECTED":
      return "관리자 설정 문제로 연결하지 못했어요.";
    case "VERIFY_FAILED":
      return `연결을 확인하지 못했어요. ${connectionErrorMessage(reason ?? "")}`;
    case "SUPERSEDED":
      return "나중에 시작한 연결이 있어 이 연결은 쓰지 않았어요.";
    case "UNAVAILABLE":
      return "서비스에 닿지 못했어요. 잠시 뒤 다시 해 주세요.";
  }
}

/** 단추 연결을 시작한다. 받은 로그인 주소로 이 창을 보낸다. */
export const startOAuth = (id: string, values: Record<string, string>) =>
  connectionCall<{ authorizeUrl: string }>(
    `/api/connections/${id}/oauth/start`,
    { method: "POST", body: { values } },
  );

/** 서비스 화면에서 돌아온 주소의 검색어를 넘긴다. */
export const completeOAuth = (query: {
  state: string | null;
  code: string | null;
  error: string | null;
  setupAction: string | null;
}) =>
  connectionCall<OAuthCompletion>("/api/connections/oauth/complete", {
    method: "POST",
    body: query,
  });

// 아래는 서버 라우트가 Control Plane 응답에서 계약의 칸만 옮길 때 쓴다.

function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function link(value: unknown): string | null {
  return connectorLinkHref(typeof value === "string" ? value : null);
}

/** 카탈로그의 `oauth` 다. 모양이 틀리면 단추 없이 입력 칸만 보이도록 null 로 둔다. */
export function safeOAuth(value: unknown): ConnectorOAuth | null {
  const item = object(value);
  if (
    !item ||
    typeof item.provider !== "string" ||
    !PROVIDER_PATTERN.test(item.provider) ||
    !Array.isArray(item.fields)
  )
    return null;
  const fields = item.fields.filter(
    (key): key is string =>
      typeof key === "string" && FIELD_KEY_PATTERN.test(key),
  );
  if (fields.length === 0) return null;
  return {
    provider: item.provider,
    ready: item.ready === true,
    fields,
    settingsUrl: link(item.settingsUrl),
    installSettingsUrl: link(item.installSettingsUrl),
  };
}

/** 연결의 `auth` 다. 없거나 틀리면 직접 입력으로 읽는다. */
export function safeAuth(value: unknown): ConnectionAuth {
  const item = object(value);
  if (
    item?.method !== "oauth" ||
    typeof item.provider !== "string" ||
    !PROVIDER_PATTERN.test(item.provider)
  )
    return { method: "manual", provider: null, reconnectRequired: false };
  return {
    method: "oauth",
    provider: item.provider,
    reconnectRequired: item.reconnectRequired === true,
  };
}

export function safeRevoke(value: unknown): ConnectionOAuthRevoke | null {
  const item = object(value);
  if (!item || (item.status !== "revoked" && item.status !== "failed"))
    return null;
  return {
    status: item.status,
    settingsUrl: link(item.settingsUrl),
    installSettingsUrl: link(item.installSettingsUrl),
  };
}

/** 시작 응답이다. 로그인 주소가 `https://` 가 아니면 쓰지 않는다. */
export function safeStart(value: unknown): { authorizeUrl: string } {
  const authorizeUrl = link(object(value)?.authorizeUrl);
  if (!authorizeUrl) throw new Error();
  return { authorizeUrl };
}

export function safeCompletion(value: unknown): OAuthCompletion {
  const item = object(value);
  if (!item || !isOAuthOutcome(item.outcome)) throw new Error();
  const connectorId =
    typeof item.connectorId === "string" &&
    CONNECTOR_ID_PATTERN.test(item.connectorId)
      ? item.connectorId
      : null;
  const authorizeUrl = link(item.authorizeUrl);
  if (item.outcome === "REAUTHORIZE" && !authorizeUrl) throw new Error();
  return {
    outcome: item.outcome,
    connectorId,
    authorizeUrl,
    reason: isOAuthReason(item.reason) ? item.reason : null,
  };
}
