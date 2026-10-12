import { spawn } from "node:child_process";
import { ATTACHMENT_DIR_ENV } from "./draft.ts";
import type { Env } from "./session.ts";

/**
 * 작업 프로세스에 넘기는 env. 연결 칸 값은 파일에 쓰지 않고 env 로만 넘긴다.
 * 작업 프로세스가 사진을 읽을 때도 같은 첨부 디렉터리로 다시 검사하도록 그 env 를 함께 넘긴다.
 */
export function workerEnvironment(env: Env) {
  const keys = [
    "PATH",
    "HOME",
    "NAVER_BLOG_BROWSER_URL",
    "NAVER_BLOG_ID",
    ATTACHMENT_DIR_ENV,
  ];
  if (env.NAVER_BLOG_JOB_DIR) keys.push("NAVER_BLOG_JOB_DIR");
  const picked: Record<string, string> = {};
  for (const key of keys) {
    const value = env[key];
    if (value !== undefined) picked[key] = value;
  }
  return picked;
}

/**
 * 분리된 작업 프로세스를 새 세션으로 띄운다. 대시보드가 MCP 서버의 프로세스 묶음을 정리해도 남는다.
 * 띄운 뒤의 오류는 호출자의 시작 확인이 시간 초과로 알린다.
 */
export function spawnWorker(
  workerEntry: string,
  jobFile: string,
  env: Record<string, string>,
) {
  const child = spawn(
    process.execPath,
    ["--no-env-file", workerEntry, "--worker", jobFile],
    {
      detached: true,
      stdio: "ignore",
      env,
    },
  );
  child.on("error", () => {});
  child.unref();
}
