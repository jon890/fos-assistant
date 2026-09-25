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
    command: `pnpm dev --hostname 127.0.0.1 --port ${WEB_PORT}`,
    cwd: join(import.meta.dirname, "../../web"),
    url: WEB_BASE_URL,
    reuseExistingServer: false,
    timeout: 120_000,
    env: {
      AUTH_SECRET,
      AUTH_GOOGLE_ID: "browser-google-id",
      AUTH_GOOGLE_SECRET: "browser-google-secret",
      AUTH_TRUST_HOST: "true",
      AUTH_URL: WEB_BASE_URL,
      ASSISTANT_JWT_SECRET: JWT_SECRET,
      CONTROL_PLANE_BASE_URL,
    },
  },
});
