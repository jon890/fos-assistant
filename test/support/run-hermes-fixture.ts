// 실제 Python plugin을 검증하는 fixture만 Hermes 테스트 영역에서 실행한다.
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

export async function runHermesFixture(name: string) {
  const child = spawn("python3", [fileURLToPath(new URL(`../../hermes/tests/fixtures/${name}`, import.meta.url)), ...process.argv.slice(2)], { stdio: "inherit" });
  const forward = (signal: NodeJS.Signals) => child.kill(signal);
  const interrupt = () => forward("SIGINT");
  const terminate = () => forward("SIGTERM");
  process.on("SIGINT", interrupt);
  process.on("SIGTERM", terminate);
  const code = await new Promise<number>(resolve => {
    child.on("error", error => { console.error(error.message); resolve(1); });
    child.on("exit", (code, signal) => resolve(code ?? (signal === "SIGINT" ? 130 : 143)));
  });
  process.removeListener("SIGINT", interrupt);
  process.removeListener("SIGTERM", terminate);
  process.exitCode = code;
}
