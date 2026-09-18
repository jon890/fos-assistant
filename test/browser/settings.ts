import { pickPort } from "../support/pick-port.ts";

export const WEB_PORT = pickPort("BROWSER_WEB_PORT", 13_000);
export const CONTROL_PLANE_PORT = pickPort("BROWSER_CONTROL_PLANE_PORT", 18_081);
export const WEB_BASE_URL = `http://127.0.0.1:${WEB_PORT}`;
export const CONTROL_PLANE_BASE_URL = `http://127.0.0.1:${CONTROL_PLANE_PORT}`;
export const AUTH_SECRET = "browser-secret-browser-secret-browser-secret";
export const JWT_SECRET = "browser-jwt-secret-browser-jwt-secret";
export const TEST_EMAIL = "browser@example.com";
