/** 관리자 「서버 비밀값」 화면의 단추 연결(OAuth) 줄이 주고받는 모양이다. client secret 과 서명 비밀의 원문은 없다. */

import type { SecretUpdatedBy } from "@/lib/server-secret-api";
import { providerName } from "./connection-oauth.ts";

/** OAuth 앱 한 줄이다. GitHub 은 플러그인마다, Google 은 모든 Google 플러그인이 한 줄을 함께 쓴다. */
export type OAuthAppSecret = {
  connectorId: string;
  connectorTitle: string;
  provider: string;
  /** client ID 와 App 이름은 공개 값이라 원문이다. 설정 전이면 null 이다. */
  clientId: string | null;
  appSlug: string | null;
  set: boolean;
  prefix: string | null;
  updatedBy: SecretUpdatedBy | null;
  updatedAt: string | null;
};

export type OAuthAppSecrets = {
  storeAvailable: boolean;
  apps: OAuthAppSecret[];
};

export type OAuthSigningSecret = {
  storeAvailable: boolean;
  set: boolean;
  updatedBy: SecretUpdatedBy | null;
  updatedAt: string | null;
  /** 토큰 통로 주소를 만들 설정이 있다. 없으면 단추 연결이 꺼져 있다. */
  passageConfigured: boolean;
  /** 새로 만들면 통로 주소를 다시 쓸 단추 연결 수다. */
  connectionCount: number;
};

export type OAuthSigningRegenerated = {
  signing: OAuthSigningSecret;
  rewrite: { total: number; failed: number };
};

export type OAuthAppInput = {
  clientId: string;
  clientSecret: string;
  appSlug: string | null;
};

/** 플러그인마다 앱을 두는 제공자다. 그 앱은 설치 화면 주소를 만들 App 이름(slug)이 필요하다. */
export function needsAppSlug(provider: string): boolean {
  return provider === "github";
}

/** 줄 제목이다. 플러그인마다인 앱은 「GitHub 앱(커리어)」, 공유 앱은 「Google 앱」 이다. */
export function oauthAppTitle(app: OAuthAppSecret): string {
  const name = `${providerName(app.provider)} 앱`;
  return needsAppSlug(app.provider) ? `${name}(${app.connectorTitle})` : name;
}

export function fetchOAuthApps(): Promise<Response> {
  return fetch("/api/admin/secrets/connector-oauth/apps", {
    cache: "no-store",
  });
}

export function saveOAuthApp(
  connectorId: string,
  input: OAuthAppInput,
): Promise<Response> {
  return fetch(`/api/admin/secrets/connector-oauth/apps/${connectorId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
}

export function clearOAuthApp(connectorId: string): Promise<Response> {
  return fetch(`/api/admin/secrets/connector-oauth/apps/${connectorId}`, {
    method: "DELETE",
  });
}

export function fetchOAuthSigning(): Promise<Response> {
  return fetch("/api/admin/secrets/connector-oauth/signing", {
    cache: "no-store",
  });
}

export function regenerateOAuthSigning(): Promise<Response> {
  return fetch("/api/admin/secrets/connector-oauth/signing/regenerate", {
    method: "POST",
  });
}

/** 서명 비밀을 새로 만들기 전 확인 창의 설명이다. 옛 표식이 바로 거절된다는 것을 알린다. */
export function signingConfirmText(connectionCount: number): string {
  return `단추 연결 ${connectionCount}개의 토큰 통로 주소를 다시 써요. 지금 실행 중인 플러그인은 다음 실행 때까지 토큰을 받지 못하고, 진행 중인 단추 연결은 다시 시도해야 해요.`;
}

/** 새로 만들기 결과를 관리자에게 보일 문장들이다. */
export function rewriteSummary(rewrite: {
  total: number;
  failed: number;
}): string[] {
  if (rewrite.total === 0) return ["새 서명 비밀을 만들었어요."];
  const lines = [
    `단추 연결 ${rewrite.total - rewrite.failed}개의 통로 주소를 다시 썼어요.`,
  ];
  if (rewrite.failed > 0)
    lines.push(
      `${rewrite.failed}개는 다시 쓰지 못했어요. 새로 만들기를 다시 누르거나 그 연결을 다시 연결해 주세요.`,
    );
  return lines;
}
