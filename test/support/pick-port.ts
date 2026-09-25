import { execFileSync } from "node:child_process";
import { closeSync, openSync, readFileSync, rmSync, writeSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

/**
 * 검사가 쓸 포트를 정한다.
 *
 * 워크트리를 둘 이상 열고 각자 검사를 돌리면 같은 포트를 다툰다.
 * 실제로 한쪽이 상대가 포트를 놓기를 400초 동안 기다린 적이 있다.
 *
 * 그래서 이렇게 정한다.
 *
 * 1. 환경 변수가 있으면 그 값을 쓴다. 고정하고 싶을 때의 길이다
 * 2. 없으면 기본 포트를 먼저 본다. 비어 있으면 그것을 쓴다
 * 3. 기본 포트를 다른 곳이 쥐고 있으면 운영체제에게 빈 포트를 받는다
 *
 * **비어 있다고 본 포트는 잠금 파일로 먼저 차지한다.**
 * 열어 보고 닫은 뒤 서버가 실제로 그 포트를 잡기까지 수십 초가 걸린다.
 * 그 사이 다른 워크트리가 같은 기본 포트를 열어 보면 역시 비어 있다고 보고, 두 실행이 같은 포트와
 * 같은 임시 파일을 쓰게 된다. 실제로 그렇게 한쪽의 검사 15건이 `ENOENT` 로 실패했다.
 * 잠금 파일은 살아 있는 프로세스의 것일 때만 존중하고, 고른 프로세스가 끝나면 지운다.
 *
 * **고른 값은 환경 변수에 적어 둔다.**
 * Playwright 는 spec 을 별도 worker 프로세스에서 돌리고 그 프로세스가 이 파일을 다시 읽는다.
 * 적어 두지 않으면 worker 마다 다른 포트를 골라 서버에 붙지 못한다.
 * worker 는 부모의 환경을 물려받으므로 한 번 적어 두면 모두 같은 값을 본다.
 *
 * @param envKey 고른 값을 적어 둘 환경 변수 이름
 * @param preferred 먼저 써 보는 포트
 */
export function pickPort(envKey: string, preferred: number): number {
  const fromEnv = process.env[envKey];
  if (fromEnv) {
    return Number(fromEnv);
  }

  let chosen = probe(preferred);
  if (!claim(chosen)) {
    chosen = probe(0);
    claim(chosen);
  }
  process.env[envKey] = String(chosen);
  return chosen;
}

/**
 * 포트 하나를 이 프로세스의 것으로 적어 둔다.
 *
 * <p>다른 살아 있는 프로세스가 이미 적어 두었으면 거짓이다. 적어 둔 프로세스가 죽었으면 넘겨받는다.
 * 운영체제가 준 포트(`probe(0)`)는 다른 실행과 겹치지 않으므로 잠금이 실패해도 그대로 쓴다.
 */
function claim(port: number): boolean {
  const path = join(tmpdir(), `fos-assistant-port-${port}.lock`);
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const fd = openSync(path, "wx");
      writeSync(fd, String(process.pid));
      closeSync(fd);
      process.once("exit", () => rmSync(path, { force: true }));
      return true;
    } catch {
      const owner = ownerOf(path);
      if (owner === "self") {
        return true;
      }
      if (owner === "other") {
        return false;
      }
      rmSync(path, { force: true });
    }
  }
  return false;
}

/** 잠금 파일을 적은 프로세스가 이 프로세스인지, 살아 있는 다른 프로세스인지, 이미 없는지. */
function ownerOf(path: string): "self" | "other" | "gone" {
  let pid: number;
  try {
    pid = Number(readFileSync(path, "utf8").trim());
  } catch {
    return "gone";
  }
  if (pid === process.pid) {
    return "self";
  }
  if (!Number.isInteger(pid) || pid <= 0) {
    return "gone";
  }
  try {
    process.kill(pid, 0);
    return "other";
  } catch {
    return "gone";
  }
}

/**
 * 포트 하나를 실제로 열어 보고 결과를 돌려준다.
 *
 * <p>비동기 확인을 별도 프로세스에서 끝내고 값만 받는다. 이 파일을 읽는 쪽은 상수를 기대하므로
 * 여기서 기다릴 수 없다.
 */
function probe(preferred: number): number {
  const script = `
    const net = require("node:net");
    const preferred = Number(process.argv[1]);
    const report = (server) => {
      const port = server.address().port;
      server.close(() => process.stdout.write(String(port)));
    };
    const first = net.createServer();
    first.once("error", () => {
      const any = net.createServer();
      any.listen(0, "127.0.0.1", () => report(any));
    });
    first.listen(preferred, "127.0.0.1", () => report(first));
  `;
  const out = execFileSync(process.execPath, ["-e", script, String(preferred)], {
    encoding: "utf8",
  });
  return Number(out.trim());
}
