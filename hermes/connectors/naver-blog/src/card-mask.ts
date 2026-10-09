/**
 * 승인 카드가 인자 글에서 가리는 자리를 찾는다. 상시 허락을 닫은 도구는 가려진 인자가 있으면 승인할 수 없어,
 * 덮어쓸 글에 그런 자리가 있으면 카드를 띄우기 전에 알린다(ADR-20261009 naver-blog-overwrite).
 * 규칙은 Control Plane 의 `ToolDetailRedactor` 를 따른다. 그쪽 규칙이 바뀌면 여기도 바꾼다.
 */

const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;
const PREFIXED_SECRET = "(?:sk-|gh[pousr]_|github_pat_|xox[a-z]*-|AIza)[A-Za-z0-9_-]+";
const TOKEN = new RegExp(
  "Bearer\\s+[^\\s\"'`,;<>}\\]]+" +
    `|${PREFIXED_SECRET}` +
    "|(?<![A-Za-z0-9])[A-Fa-f0-9]{32,}(?![A-Za-z0-9])" +
    "|(?<![A-Za-z0-9_+/=-])[A-Za-z0-9+/]{32,}={0,2}(?![A-Za-z0-9_+/=-])" +
    "|(?<![A-Za-z0-9_+/=-])[A-Za-z0-9_-]{32,}={0,2}(?![A-Za-z0-9_+/=-])",
  "i",
);
const JWT = /(?<![A-Za-z0-9_-])([A-Za-z0-9_-]+)\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+(?![A-Za-z0-9_-])/g;
const AUTH_HEADER = /\b(?:authorization|cookie)\s*[:=]\s*[^\r\n]+/im;
const ASSIGNMENT =
  /["']?([A-Za-z][A-Za-z0-9_-]*)["']?\s*[:=]\s*(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s,;}&]+)/gi;
const SECRET_KEYS = new Set([
  "token",
  "secret",
  "password",
  "passwd",
  "apikey",
  "authorization",
  "cookie",
  "credential",
  "credentials",
  "privatekey",
  "accesskey",
  "clientsecret",
]);

function secretKey(key: string) {
  const normalized = key.replace(/[_-]/g, "").toLowerCase();
  return (
    SECRET_KEYS.has(normalized) ||
    ["token", "secret", "password", "privatekey"].some((end) => normalized.endsWith(end))
  );
}

/** JWT 모양의 첫 조각이 `alg` 칸을 가진 JSON 머리면 참이다. 도메인과 날짜는 거짓이다. */
function jwtHeader(part: string) {
  try {
    const header = JSON.parse(Buffer.from(part, "base64url").toString("utf8"));
    return header !== null && typeof header === "object" && !Array.isArray(header) && "alg" in header;
  } catch {
    return false;
  }
}

/** 승인 카드가 이 글의 일부를 가리면 참이다. */
export function cardWouldMask(text: string) {
  const withoutUuids = text.replace(UUID, " ");
  if (TOKEN.test(withoutUuids) || AUTH_HEADER.test(withoutUuids)) return true;
  for (const match of withoutUuids.matchAll(JWT)) if (jwtHeader(match[1]!)) return true;
  for (const match of withoutUuids.matchAll(ASSIGNMENT)) if (secretKey(match[1]!)) return true;
  return false;
}
