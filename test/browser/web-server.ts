import { spawn, spawnSync } from "node:child_process";
import { cp } from "node:fs/promises";
import { join } from "node:path";

/**
 * 브라우저 검사가 띄우는 웹 서버다. `playwright.config.ts` 의 `webServer` 가 부른다.
 *
 * <p>기본은 운영과 같은 빌드 결과를 띄운다. `BROWSER_WEB_SERVER=dev` 를 주면 개발 서버를 띄운다.
 * 화면을 고치며 같은 검사를 되풀이할 때 빌드를 기다리지 않으려는 길이다.
 *
 * <p>**빌드한 서버를 띄울 때는 언제나 먼저 빌드한다.** 한 번에 10초 남짓이라 이미 있는 빌드가 지금 소스와
 * 같은지 따지는 것보다 싸다. 수정 시각으로 따지면 지운 파일과 브랜치 전환과 빌드 때의 환경 변수를 놓쳐
 * 옛 화면을 검사할 수 있다.
 *
 * <p>빌드에는 `Dockerfile` 과 같은 자리표시자를 주고, 검사용 값은 서버를 띄울 때만 준다. 운영 이미지도
 * 자리표시자로 빌드하고 실행할 때 값을 받으므로, 빌드 때 값을 읽어 굳히는 코드가 생기면 여기서 드러난다.
 */

const WEB_DIR = join(import.meta.dirname, "../../web");
const STANDALONE_DIR = join(WEB_DIR, ".next/standalone");

const BUILD_PLACEHOLDER_ENV = {
  NEXT_TELEMETRY_DISABLED: "1",
  AUTH_SECRET: "build-time-placeholder",
  ASSISTANT_JWT_SECRET: "build-time-placeholder",
  CONTROL_PLANE_BASE_URL: "http://build-time-placeholder",
  AUTH_GOOGLE_ID: "build-time-placeholder",
  AUTH_GOOGLE_SECRET: "build-time-placeholder",
};

const mode = process.env.BROWSER_WEB_SERVER ?? "build";
const port = process.argv[2];
if (!port) throw new Error("포트를 첫째 인자로 주어야 한다.");

console.error(`브라우저 검사의 웹 서버: ${mode === "dev" ? "개발 서버" : "빌드한 서버"}`);
if (mode === "dev") {
  run("pnpm", ["dev", "--hostname", "127.0.0.1", "--port", port], {});
} else if (mode === "build") {
  build();
  // standalone 결과는 정적 파일을 담지 않는다. `Dockerfile` 이 옮기는 것과 같은 두 디렉터리를 옮긴다.
  await cp(join(WEB_DIR, ".next/static"), join(STANDALONE_DIR, ".next/static"), { recursive: true });
  await cp(join(WEB_DIR, "public"), join(STANDALONE_DIR, "public"), { recursive: true });
  run("node", [join(STANDALONE_DIR, "server.js")], {
    NODE_ENV: "production",
    NEXT_TELEMETRY_DISABLED: "1",
    // macOS 셸은 HOSTNAME 에 기계 이름을 넣어 둔다. 여기서 정하지 않으면 standalone 서버가 그 이름의 주소로 듣는다.
    HOSTNAME: "127.0.0.1",
    PORT: port,
  });
} else {
  throw new Error(`BROWSER_WEB_SERVER 는 build 나 dev 여야 한다: ${mode}`);
}

function build(): void {
  // Playwright 는 웹 서버의 표준 출력을 버리고 표준 오류만 보인다. 빌드가 왜 실패했는지 보이도록 둘 다 표준 오류로 보낸다.
  const result = spawnSync("pnpm", ["build"], {
    cwd: WEB_DIR,
    env: { ...process.env, ...BUILD_PLACEHOLDER_ENV },
    stdio: ["ignore", 2, 2],
  });
  if (result.status !== 0) {
    throw new Error(`브라우저 검사 전에 웹을 빌드하지 못했다: 종료 코드 ${result.status}`);
  }
}

/** 서버를 띄우고 그 서버가 끝나면 같은 코드로 끝난다. Playwright 가 보내는 종료 신호를 서버에 넘긴다. */
function run(command: string, args: string[], env: Record<string, string>): void {
  const server = spawn(command, args, {
    cwd: WEB_DIR,
    env: { ...process.env, ...env },
    stdio: "inherit",
  });
  for (const signal of ["SIGINT", "SIGTERM"] as const) {
    process.on(signal, () => server.kill(signal));
  }
  server.on("exit", (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
}
