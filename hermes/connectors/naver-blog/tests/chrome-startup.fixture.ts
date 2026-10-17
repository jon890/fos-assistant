import { join } from "node:path";

// 실제 Chrome 없이 조기 종료와 DevTools HTTP 실패의 진단을 재현한다.
export async function fakeChromeStartup(scenario: "exit" | "http" | "timeout") {
  await Bun.write(
    Bun.stderr,
    Array.from(
      { length: 100 },
      (_, i) => `startup-${i}: ${"x".repeat(200)}\n`,
    ).join("") + "startup-final-marker\n",
  );
  if (scenario === "exit") process.exit(23);
  if (scenario === "timeout") {
    setInterval(() => {}, 1_000);
    return;
  }

  const profile = process.argv
    .find((arg) => arg.startsWith("--user-data-dir="))!
    .slice("--user-data-dir=".length);
  const server = Bun.serve({
    hostname: "127.0.0.1",
    port: 0,
    fetch: () => new Response("synthetic failure", { status: 503 }),
  });
  await Bun.write(join(profile, "DevToolsActivePort"), `${server.port}\n`);
}
