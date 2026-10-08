import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { createRequire } from "node:module";
import vm from "node:vm";
import ts from "../../web/node_modules/typescript/lib/typescript.js";

const originalGoogleId = process.env.AUTH_GOOGLE_ID;
const originalGoogleSecret = process.env.AUTH_GOOGLE_SECRET;

test.afterEach(() => {
  if (originalGoogleId === undefined) delete process.env.AUTH_GOOGLE_ID;
  else process.env.AUTH_GOOGLE_ID = originalGoogleId;
  if (originalGoogleSecret === undefined) delete process.env.AUTH_GOOGLE_SECRET;
  else process.env.AUTH_GOOGLE_SECRET = originalGoogleSecret;
});

type AuthConfig = {
  callbacks: {
    signIn: (input: {
      profile?: { email?: string | null };
    }) => Promise<boolean>;
  };
  events: {
    signIn: (input: { user: { email?: string | null } }) => Promise<void>;
  };
};

async function loadAuth(
  record: (email: string) => Promise<void>,
): Promise<AuthConfig> {
  process.env.AUTH_GOOGLE_ID = "test-google-id";
  process.env.AUTH_GOOGLE_SECRET = "test-google-secret";
  const source = await readFile(
    new URL("../../web/src/auth.ts", import.meta.url),
    "utf8",
  );
  const compiled = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022,
    },
  }).outputText;
  let settings: AuthConfig | undefined;
  const localRequire = (name: string) => {
    if (name === "next-auth")
      return {
        __esModule: true,
        default: (config: AuthConfig) => {
          settings = config;
          return {
            handlers: {},
            auth: async () => null,
            signIn: async () => {},
            signOut: async () => {},
          };
        },
      };
    if (name === "next-auth/providers/google")
      return { __esModule: true, default: () => ({ id: "google" }) };
    if (name === "@/lib/control-plane")
      return { isSignInAllowed: async () => true };
    if (name === "@/lib/signin-activity") return { recordSignIn: record };
    return createRequire(import.meta.url)(name);
  };
  const module = { exports: {} };
  vm.runInNewContext(compiled, {
    module,
    exports: module.exports,
    require: localRequire,
    process,
  });
  assert.ok(settings, "NextAuth 설정을 회수하지 못했다");
  return settings;
}

test("실제 auth 설정의 로그인 이벤트가 이메일이 있을 때만 활동을 기록한다", async () => {
  const emails: string[] = [];
  const config = await loadAuth(async (email) => {
    emails.push(email);
  });
  await config.events.signIn({ user: { email: "user@example.com" } });
  await config.events.signIn({ user: {} });
  assert.deepEqual(emails, ["user@example.com"]);
});

test("허용 판정 callback은 활동을 기록하지 않고 이벤트 실패는 경계까지 전한다", async () => {
  let calls = 0;
  const config = await loadAuth(async () => {
    calls += 1;
    throw new Error("record failed");
  });
  assert.equal(
    await config.callbacks.signIn({ profile: { email: "user@example.com" } }),
    true,
  );
  assert.equal(calls, 0);
  await assert.rejects(
    config.events.signIn({ user: { email: "user@example.com" } }),
    /record failed/,
  );
});
