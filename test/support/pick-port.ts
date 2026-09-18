import { execFileSync } from "node:child_process";

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

  const chosen = probe(preferred);
  process.env[envKey] = String(chosen);
  return chosen;
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
