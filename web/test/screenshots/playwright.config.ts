import { join } from "node:path";
import { tmpdir } from "node:os";
import playwright from "../../web/node_modules/@playwright/test/index.js";
import browserConfig from "../browser/playwright.config.ts";
import { WEB_PORT } from "../browser/settings.ts";

const { defineConfig } = playwright;

// 관리자의 표시 이름은 처음 만들어질 때 굳는다. globalSetup 이 돌기 전에 지어낸 이름을 정해 둔다.
process.env.BROWSER_ADMIN_NAME = "Alex";

/**
 * README 에 싣는 화면을 찍는 설정이다. 검사가 아니라서 `pnpm test:browser` 와 CI 는 이 디렉터리를 돌리지 않는다.
 *
 * <p>띄우는 것은 브라우저 검사와 같다. 빌드한 웹, H2 를 쓰는 Control Plane, 가짜 Hermes 를 `../browser` 의 설정
 * 그대로 띄우고, 화면에 보이는 값은 `tour.shots.ts` 가 넣는 지어낸 데이터뿐이다.
 */
export default defineConfig({
  ...browserConfig,
  testDir: ".",
  testMatch: "*.shots.ts",
  globalSetup: "../browser/fixtures.ts",
  outputDir: join(tmpdir(), `fos-assistant-screenshots-results-${WEB_PORT}`),
  // 화면을 차례로 찍는 한 흐름이다. 다시 돌리면 앞에서 넣은 데이터가 겹친다.
  retries: 0,
  timeout: 180_000,
  projects: [
    {
      name: "tour",
      use: {
        viewport: { width: 1280, height: 800 },
        deviceScaleFactor: 1,
        colorScheme: "light",
        locale: "ko-KR",
        timezoneId: "UTC",
      },
    },
  ],
});
