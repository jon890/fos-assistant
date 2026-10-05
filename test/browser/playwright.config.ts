import { join } from "node:path";
import { tmpdir } from "node:os";
import playwright from "../../web/node_modules/@playwright/test/index.js";
import {
  AUTH_SECRET,
  CONTROL_PLANE_BASE_URL,
  JWT_SECRET,
  WEB_BASE_URL,
  WEB_PORT,
} from "./settings.ts";

const { defineConfig } = playwright;

export default defineConfig({
  testDir: ".",
  testMatch: "*.spec.ts",
  // 모든 spec 이 globalSetup 이 띄운 Control Plane 하나와 H2 메모리 DB 를 함께 쓰고, 몇 spec 은 그 상태를 잠시 바꿨다 되돌린다
  // (legacy-conversation-url.spec.ts 가 그렇다). 그래서 검사를 나란히 돌리지 않는다. 나눠 돌리는 것은 CI 의 shard 가 맡는다.
  fullyParallel: false,
  workers: 1,
  reporter: "list",
  // 워크트리 여럿이 함께 돌 때 서로의 결과를 지우지 않게 포트로 나눈다. 실행을 시작할 때 이 디렉터리를 비운다.
  outputDir: join(tmpdir(), `fos-assistant-playwright-results-${WEB_PORT}`),
  globalSetup: "./fixtures.ts",
  use: {
    baseURL: WEB_BASE_URL,
    trace: "retain-on-failure",
  },
  projects: [
    {
      name: "mobile",
      use: { viewport: { width: 390, height: 844 } },
    },
    {
      name: "desktop",
      use: { viewport: { width: 1280, height: 900 } },
    },
  ],
  webServer: {
    // 기본은 빌드한 서버다. `BROWSER_WEB_SERVER=dev` 면 개발 서버를 띄운다. 까닭은 web-server.ts 에 있다.
    command: `node ../test/browser/web-server.ts ${WEB_PORT}`,
    cwd: join(import.meta.dirname, "../../web"),
    url: WEB_BASE_URL,
    reuseExistingServer: false,
    // 빌드한 서버는 이 시간 안에 빌드까지 마친다.
    timeout: 180_000,
    env: {
      AUTH_SECRET,
      AUTH_GOOGLE_ID: "browser-google-id",
      AUTH_GOOGLE_SECRET: "browser-google-secret",
      AUTH_TRUST_HOST: "true",
      AUTH_URL: WEB_BASE_URL,
      ASSISTANT_JWT_SECRET: JWT_SECRET,
      CONTROL_PLANE_BASE_URL,
      // 앱 이름은 실행할 때 읽는다. 빌드 때의 값과 다른 이름을 주어, 빌드에 굳었으면 검사가 실패하게 한다.
      APP_NAME: "검사용 비서",
    },
  },
});
