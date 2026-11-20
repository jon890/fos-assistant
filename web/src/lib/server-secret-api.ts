/** 관리자 「서버 비밀값」 화면이 주고받는 모양이다. 원문을 담는 칸은 없다(서버 비밀값 스펙 REQ2, REQ3). */

export type SecretUpdatedBy = { id: number; name: string | null };

export type BrowserGatewaySecret = {
  set: boolean;
  prefix: string | null;
  updatedBy: SecretUpdatedBy | null;
  updatedAt: string | null;
  gatewayUrlConfigured: boolean;
  /** 브라우저를 쓰는 플러그인 바인딩 수. 플러그인 목록을 읽지 못하면 null */
  bindingCount: number | null;
};

export type ServerSecretOverview = {
  storeAvailable: boolean;
  browserGateway: BrowserGatewaySecret;
};

export type BindingReinstall = {
  total: number;
  restartPending: number;
  failed: number;
  catalogUnavailable: boolean;
};

export type GatewayRegenerated = ServerSecretOverview & {
  reinstall: BindingReinstall;
};

export function fetchServerSecrets(): Promise<Response> {
  return fetch("/api/admin/secrets", { cache: "no-store" });
}

export function regenerateGatewaySecret(): Promise<Response> {
  return fetch("/api/admin/secrets/browser-gateway/regenerate", {
    method: "POST",
  });
}

export function deleteGatewaySecret(): Promise<Response> {
  return fetch("/api/admin/secrets/browser-gateway", { method: "DELETE" });
}

/** 다시 만들기 확인 창의 설명이다. 바인딩 수를 읽지 못했으면 수 대신 「붙은」 이라고 쓴다(서버 비밀값 스펙 REQ18). */
export function regenerateConfirmText(bindingCount: number | null): string {
  const target =
    bindingCount === null
      ? "붙은 브라우저 플러그인 바인딩을"
      : `브라우저 플러그인 바인딩 ${bindingCount}개를`;
  return `${target} 다시 설치합니다. Hermes 를 다시 띄우기(반영 완료) 전까지 그 플러그인은 동작하지 않습니다.`;
}

/** 다시 만들기 결과를 관리자에게 보일 문장들이다. */
export function reinstallSummary(result: BindingReinstall): string[] {
  if (result.catalogUnavailable)
    return [
      "비밀은 바뀌었지만 플러그인 목록을 읽지 못해 바인딩을 다시 설치하지 못했어요. Hermes 가 돌아온 뒤 다시 만들어 주세요.",
    ];
  if (result.total === 0) return ["새 비밀을 만들었어요."];
  const lines = [
    `${result.total - result.failed}개를 다시 설치했어요. 반영 완료 전까지 브라우저 플러그인은 동작하지 않습니다.`,
  ];
  if (result.failed > 0)
    lines.push(
      `${result.failed}개는 다시 설치하지 못했어요. 바인딩 화면에서 확인하고, 그대로면 비밀을 다시 만들어 주세요.`,
    );
  return lines;
}
