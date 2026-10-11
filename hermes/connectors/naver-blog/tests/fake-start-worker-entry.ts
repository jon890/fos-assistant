import { readFile, unlink, writeFile } from "node:fs/promises";
import { dirname, basename } from "node:path";
import { finishState, inputFile, readState, releaseLock, updateState } from "../src/jobs.ts";

/** 파일 신호를 읽을 때까지 기다린다. 진행·종료 상태를 내기 전에 시험 쪽이 queued 상태를 확인한다. */
async function waitForSignal(file: string) {
  const deadline = Date.now() + 5_000;
  for (;;) {
    try {
      await readFile(file);
      return;
    } catch {
      if (Date.now() >= deadline) throw new Error("START_FIXTURE_SIGNAL_TIMEOUT");
      await Bun.sleep(10);
    }
  }
}

/** 실제 분리 프로세스에서 queued 상태 뒤에 요청한 상태만 기록하는 시작 응답 대역. */
async function main() {
  const jobFile = process.argv[process.argv.indexOf("--worker") + 1]!;
  const dir = dirname(jobFile);
  const jobId = basename(jobFile, ".json");
  const input = JSON.parse(await readFile(inputFile(dir, jobId), "utf8"));
  const mode = input.title;
  await updateState(dir, jobId, { pid: process.pid, heartbeat_at: new Date().toISOString() });
  await writeFile(`${jobFile}.ready`, "");
  await waitForSignal(`${jobFile}.continue`);
  if (mode === "queued") {
    // queued/pid만으로는 시작 확인이 끝나지 않는 경우. 시험이 START_UNKNOWN을 받은 뒤 신호를 준다.
    await waitForSignal(`${jobFile}.finish`);
  } else if (mode === "running") {
    await updateState(dir, jobId, { stage: "fill" });
    await waitForSignal(`${jobFile}.finish`);
  } else if (mode === "unknown") {
    // 저장 단추 기록도 queued 상태에서 쓴다. 서버가 중간 running/save를 먼저 보는 경쟁을 없앤다.
    await updateState(dir, jobId, { save_clicked: true });
  }
  await unlink(inputFile(dir, jobId));
  await finishState(dir, jobId, mode === "succeeded"
    ? { status: "succeeded", result: { state: null, saved_before: 0, saved_after: 1 } }
    : mode === "unknown"
      ? { status: "unknown", error: { code: "save_unconfirmed", stage: "save" } }
      : { status: "failed", error: { code: "editor_failed", stage: "fill" } });
  await releaseLock(dir, jobId);
  const finished = await readState(dir, jobId);
  await writeFile(`${jobFile}.done`, JSON.stringify({ pid: process.pid, status: finished?.status }));
}

if (import.meta.main) await main();
