import { runGmailServer } from "../src/server.ts";

await runGmailServer({
  tokenUrl: process.env.TEST_GMAIL_TOKEN_URL,
  apiBase: process.env.TEST_GMAIL_API_BASE,
  env: {
    GMAIL_OAUTH_CLIENT_ID: "child-test-client",
    GMAIL_OAUTH_CLIENT_SECRET: "child-test-secret",
    GMAIL_OAUTH_REFRESH_TOKEN: "child-test-refresh",
  },
});
