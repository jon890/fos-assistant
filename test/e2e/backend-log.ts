/** 합성 E2E 로그에도 인증 값과 사용자 본문을 남기지 않는다. 가림 표기는 제품과 맞춘다. */
export function maskBackendLog(log: string, secrets: readonly string[]): string {
  const maskValue = (value: string): string => {
    for (const secret of secrets) {
      if (secret.length === 0) continue;
      value = value.replaceAll(secret, "[가림]")
        .replaceAll(JSON.stringify(secret).slice(1, -1), "[가림]");
    }
    return value
    .replace(/\b(?:authorization|cookie)\s*[:=]\s*[^\r\n]+/gi, "인증=[가림]")
    .replace(/\bBearer\s+[^\s,;]+/gi, "Bearer [가림]")
    .replace(/\b[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, (value) =>
      value.startsWith("eyJ") ? "[가림]" : value)
    .replace(/\b(?:fos_svc_|sk-|gh[pousr]_|github_pat_|xox[a-z]*-|AIza)[A-Za-z0-9_-]+/gi, "[가림]")
    .replace(/[A-Za-z0-9_-]{32,}/g, "[가림]")
    .replace(/https?:\/\/[^\s"'<>\])]+/gi, "[주소 가림]")
    .replace(/\b[A-Za-z0-9_.+-]+@[A-Za-z0-9.-]+\b/g, "[계정 가림]");
  };
  const privateField = /^(?:authorization|cookie|token|secret|password|passwd|api[_-]?key|credential|private[_-]?key|access[_-]?key|client[_-]?secret)$/i;
  const bodyField = /^(?:input|output|text|content|body|detail|preview|goal|instructions|result)$/i;
  const maskJson = (value: unknown): unknown => {
    if (Array.isArray(value)) return value.map(maskJson);
    if (value !== null && typeof value === "object") {
      return Object.fromEntries(Object.entries(value).map(([key, item]) => [maskValue(key),
        privateField.test(key) ? "[가림]" : bodyField.test(key) ? "[본문 가림]"
          : key.toLowerCase() === "payload" && typeof item === "string" ? maskPayload(item) : maskJson(item)]));
    }
    return typeof value === "string" ? maskValue(value) : value;
  };
  const maskPayload = (value: string): unknown => {
    try {
      const decoded: unknown = JSON.parse(value);
      return decoded !== null && typeof decoded === "object" ? maskJson(decoded) : "[본문 가림]";
    } catch {
      return "[본문 가림]";
    }
  };
  return log.split("\n").map((line) => {
    // 로그 앞부분을 남기고 JSON 필드명과 escape를 디코딩한 뒤 값을 가린다.
    const jsonStart = line.search(/\{|\[\s*(?:\{|"|\])/);
    if (jsonStart >= 0) {
      const prefix = maskValue(line.slice(0, jsonStart));
      try {
        return prefix + JSON.stringify(maskJson(JSON.parse(line.slice(jsonStart))));
      } catch {
        // 불완전한 JSON의 뒷부분이나 escape된 본문을 부분 치환으로 남기지 않는다.
        return prefix + "[본문 가림]";
      }
    }
    const dump = /["']?(?:payload|input|output|text|content|body|detail|preview|goal|instructions|result)["']?\s*[:=]/i.exec(line);
    if (dump) return maskValue(line.slice(0, dump.index)) + "[본문 가림]";
    return maskValue(line).replace(/(["']?(?:token|secret|password|passwd|api[_-]?key|credential|private[_-]?key|access[_-]?key|client[_-]?secret)["']?\s*[:=]\s*)("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s,;}]+)/gi, "$1[가림]");
  }).join("\n");
}

/** 관련 WARN은 다른 오류의 스택이 많아도 별도 묶음으로 보존한다. */
export function backendFailureExcerpt(log: string): string {
  const lines = log.split("\n");
  const related = lines.filter((line) => /WARN/.test(line)
    && /HttpHermesRunsClient|SubagentUsageReconciler/.test(line));
  const errors = lines.filter((line) => /ERROR|Caused by|at com\.bifos/.test(line)).slice(-30);
  return [...related, ...(errors.length > 0 ? errors : lines.slice(-20))].join("\n");
}
