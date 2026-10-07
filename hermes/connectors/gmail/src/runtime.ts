import { MINIMUM_BUN_VERSION, PROXY_ENVIRONMENT_KEYS } from "./constants.ts";

export type Env = Record<string, string | undefined>;

/** 프록시가 상속된 Bun은 시작 시 연결 대상을 캐시하므로 깨끗한 자식으로 다시 시작한다. */
export function hasProxyEnvironment(env: Env) {
  return PROXY_ENVIRONMENT_KEYS.some((key) => Boolean(env[key]));
}

export function proxyFreeEnvironment(env: Env): Env {
  const cleaned = { ...env };
  for (const key of PROXY_ENVIRONMENT_KEYS) delete cleaned[key];
  return cleaned;
}

/** 테스트처럼 이미 실행 중인 factory에서도 다음 요청이 프록시 값을 읽지 않게 한다. */
export function clearProxyEnvironment() {
  for (const key of PROXY_ENVIRONMENT_KEYS) delete process.env[key];
}

/** 최소 Bun 1.3.14 release 이상인지 SemVer 숫자로 비교한다. */
export function isSupportedBunVersion(version: string) {
  const match =
    /^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/.exec(
      version,
    );
  if (!match) return false;

  const major = Number(match[1]);
  const minor = Number(match[2]);
  const patch = Number(match[3]);
  const prerelease = match[4] !== undefined;
  if (major !== MINIMUM_BUN_VERSION[0]) return major > MINIMUM_BUN_VERSION[0];
  if (minor !== MINIMUM_BUN_VERSION[1]) return minor > MINIMUM_BUN_VERSION[1];
  if (patch !== MINIMUM_BUN_VERSION[2]) return patch > MINIMUM_BUN_VERSION[2];
  return !prerelease;
}
