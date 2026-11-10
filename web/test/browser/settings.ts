import { pickPort } from "../support/pick-port.ts";

export const WEB_PORT = pickPort("BROWSER_WEB_PORT", 13_000);
export const CONTROL_PLANE_PORT = pickPort("BROWSER_CONTROL_PLANE_PORT", 18_081);

/**
 * 이 실행을 가리키는 번호다. 실행마다 나뉘어야 하는 임시 파일 이름에 쓴다.
 *
 * <p>포트처럼 환경 변수에 적어 두어 Playwright 의 worker 프로세스들도 같은 값을 본다.
 */
export const RUN_ID = (process.env.BROWSER_RUN_ID ??= String(process.pid));
export const WEB_BASE_URL = `http://127.0.0.1:${WEB_PORT}`;
export const CONTROL_PLANE_BASE_URL = `http://127.0.0.1:${CONTROL_PLANE_PORT}`;
export const AUTH_SECRET = "browser-secret-browser-secret-browser-secret";
export const JWT_SECRET = "browser-jwt-secret-browser-jwt-secret";
export const TEST_EMAIL = "browser@example.com";
