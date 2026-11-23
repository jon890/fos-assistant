import { spawnSync } from "node:child_process";
import { createServer } from "node:net";
import { pickPort } from "../support/pick-port.ts";

const APP_PORT_ENV = "E2E_APP_PORT";

export async function pickAppPort(defaultPort: number): Promise<number> {
  const explicitPort = process.env[APP_PORT_ENV];
  const port = explicitPort ? Number(explicitPort) : defaultPort;
  const conflict = await portConflict(port);
  if (conflict === undefined) return pickPort(APP_PORT_ENV, port);
  if (explicitPort) {
    throw new Error(`${conflict} 확인한 뒤 끝내고 다시 돌리세요.`);
  }

  // pickPort의 바인딩 검사도 Java의 IPv6 수신 소켓을 놓칠 수 있으므로 바로 빈 포트를 받는다.
  const fallback = pickPort(APP_PORT_ENV, 0);
  console.warn(`경고: ${conflict.replace(/[\r\n]+/g, " ")} 빈 포트 ${fallback}에서 계속합니다.`);
  return fallback;
}

async function portConflict(port: number): Promise<string | undefined> {
  // macOS에서는 Java의 IPv6 소켓이 IPv4 요청도 받는 상태에서 IPv4 바인딩이 성공할 수 있다.
  const owners = spawnSync("lsof", ["-nP", "-t", `-iTCP:${port}`, "-sTCP:LISTEN"], {
    encoding: "utf8",
    timeout: 5_000,
  });
  const pids = new Set(owners.stdout?.match(/^\d+$/gm));
  if (pids.size === 0) {
    try {
      await new Promise<void>((resolve, reject) => {
        const probe = createServer();
        probe.once("error", reject);
        probe.listen(port, "127.0.0.1", () => {
          probe.close((error) => error ? reject(error) : resolve());
        });
      });
      return undefined;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "EADDRINUSE") throw error;
    }
  }

  const details = [...pids].map((pid) => {
    const command = spawnSync("ps", ["-ww", "-p", pid, "-o", "command="], {
      encoding: "utf8",
      timeout: 5_000,
    }).stdout?.trim();
    return `PID ${pid} (${command || "명령 줄 확인 불가"})`;
  });
  return (
    `TCP 포트 ${port} 사용 중. ${details.join(", ") || "PID와 명령 줄을 확인하지 못했습니다."} ` +
    "앞 e2e 실행이 남긴 프로세스일 수 있습니다."
  );
}
