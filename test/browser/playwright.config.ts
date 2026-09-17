import { join } from "node:path";
import { tmpdir } from "node:os";
import playwright from "../../web/node_modules/@playwright/test/index.js";
import {
  AUTH_SECRET,
  CONTROL_PLANE_BASE_URL,
  JWT_SECRET,
  TEST_EMAIL,
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
  outputDir: join(tmpdir(), "fos-assistant-playwright-results"),
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
      ASSISTANT_ALLOWED_EMAILS: TEST_EMAIL,
      ASSISTANT_JWT_SECRET: JWT_SECRET,
      CONTROL_PLANE_BASE_URL,
    },
  },
});
