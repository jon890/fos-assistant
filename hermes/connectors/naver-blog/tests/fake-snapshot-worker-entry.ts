import { appendFile } from "node:fs/promises";
import {
  fingerprintOf,
  SnapshotGuardError,
} from "../src/content-snapshot-guard.ts";
import { ATTACHMENT_DIR_ENV, readPhoto } from "../src/draft.ts";
import type { runDraft } from "../src/editor/run.ts";
import { runWorker } from "../src/worker.ts";

/**
 * 브라우저 대신 업로드와 저장 클릭을 작업 파일 옆 기록 파일에 한 줄씩 남기는 `runDraft` 대역.
 * 실제 `photos` 의 guard 순서(읽기 → 지문 → authorize → 업로드)를 그대로 따른다.
 * 기록은 독립 카운터다. 결과의 저장 수나 manifest 를 업로드·클릭 증거로 세지 않는다.
 */
export function recordingRunDraft(jobFile: string): typeof runDraft {
  return async (env, blocks, input, onStage, _signal, _options, guard) => {
    await onStage("open");
    await onStage("photos");
    const images = blocks.filter((block) => block.type === "image");
    for (const [index, image] of images.entries()) {
      const photo = await readPhoto(input, image.file, env[ATTACHMENT_DIR_ENV]);
      if (guard) {
        const entry = guard.manifest[index]!;
        if (fingerprintOf(photo.bytes) !== entry.sourceFingerprint)
          throw new SnapshotGuardError("photo_mismatch");
        await guard.authorizePhoto(entry.position);
      }
      await appendFile(`${jobFile}.uploads`, `${fingerprintOf(photo.bytes)}\n`);
    }
    await onStage("save");
    await onStage("save_clicking");
    await appendFile(`${jobFile}.clicks`, "click\n");
    return { state: null, savedBefore: 3, savedAfter: 4 };
  };
}

if (import.meta.main) {
  const index = process.argv.indexOf("--worker");
  const jobFile = index >= 0 ? process.argv[index + 1] : undefined;
  if (jobFile) {
    await appendFile(
      `${jobFile}.env`,
      JSON.stringify(Object.keys(process.env).sort()) + "\n",
    );
    await runWorker(jobFile, { runDraft: recordingRunDraft(jobFile) });
    await appendFile(
      `${jobFile}.env-after`,
      JSON.stringify(Object.keys(process.env).sort()) + "\n",
    );
  }
  process.exit(0);
}
