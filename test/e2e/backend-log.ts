/** 합성 E2E 로그에도 인증 값과 사용자 본문을 남기지 않는다. 가림 표기는 제품과 맞춘다. */
export function maskBackendLog(log: string, secrets: readonly string[]): string {
  for (const secret of secrets) {
    if (secret.length > 0) log = log.replaceAll(secret, "[가림]");
  }
  return log
    .replace(/\b(?:authorization|cookie)\s*[:=]\s*[^\r\n]+/gi, "인증=[가림]")
    .replace(/\bBearer\s+[^\s,;]+/gi, "Bearer [가림]")
    .replace(/\b[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, (value) =>
      value.startsWith("eyJ") ? "[가림]" : value)
    .replace(/\b(?:fos_svc_|sk-|gh[pousr]_|github_pat_|xox[a-z]*-|AIza)[A-Za-z0-9_-]+/gi, "[가림]")
    .replace(/[A-Za-z0-9_-]{32,}/g, "[가림]")
    .replace(/https?:\/\/[^\s"'<>\])]+/gi, "[주소 가림]")
    .replace(/\b[A-Za-z0-9_.+-]+@[A-Za-z0-9.-]+\b/g, "[계정 가림]")
    .replace(/(["']?(?:token|secret|password|passwd|api[_-]?key|credential|private[_-]?key|access[_-]?key|client[_-]?secret)["']?\s*[:=]\s*)("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s,;}]+)/gi, "$1[가림]")
    // 본문 칸이 들어간 줄은 이후 내용을 모두 가린다. 다중 줄 JSON 문자열의 escape도 원문으로 남기지 않는다.
    .replace(/(["']?(?:input|output|text|content|body|detail|preview|goal|instructions)["']?\s*[:=]\s*)[^\r\n]*/gi,
      "$1[본문 가림]");
}

/** 관련 WARN은 다른 오류의 스택이 많아도 별도 묶음으로 보존한다. */
export function backendFailureExcerpt(log: string): string {
  const lines = log.split("\n");
  const related = lines.filter((line) => /WARN/.test(line)
    && /HttpHermesRunsClient|SubagentUsageReconciler/.test(line));
  const errors = lines.filter((line) => /ERROR|Caused by|at com\.bifos/.test(line)).slice(-30);
  return [...related, ...(errors.length > 0 ? errors : lines.slice(-20))].join("\n");
}
