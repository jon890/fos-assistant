export type ServiceTokenStatus = "active" | "expiring" | "expired" | "revoked";

/** 만료까지 이 날수 이하로 남으면 「곧 만료」 로 보인다(ADR-056). */
export const EXPIRING_WITHIN_DAYS = 14;

const DAY_MS = 24 * 60 * 60 * 1000;

/** 폐기가 먼저고, 그다음 만료, 그다음 곧 만료다. 폐기한 토큰은 만료 시각이 지났어도 폐기로 보인다. */
export function serviceTokenStatus(
  token: { expiresAt: string; revokedAt: string | null },
  now: Date,
): ServiceTokenStatus {
  if (token.revokedAt) return "revoked";
  const remaining = new Date(token.expiresAt).getTime() - now.getTime();
  if (remaining <= 0) return "expired";
  if (remaining <= EXPIRING_WITHIN_DAYS * DAY_MS) return "expiring";
  return "active";
}
