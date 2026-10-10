import { ATTACHMENT_DIR_ENV, readPhoto } from "../src/draft.ts";
import type { runOverwrite } from "../src/editor/overwrite.ts";
import type { runDraft } from "../src/editor/run.ts";
import { runWorker } from "../src/worker.ts";

/**
 * 시험이 실제 작업 프로세스를 띄울 때 쓰는 대역. 브라우저에 닿지 않고 잠깐 기다린 뒤 성공한다.
 * 기다리는 동안 시험이 작업 프로세스의 프로세스 묶음을 확인한다.
 * 사진은 실제 작업처럼 작업 프로세스의 env 가 가리키는 첨부 디렉터리로 다시 검사해 읽는다.
 */
export const fakeRunDraft: typeof runDraft = async (env, blocks, input, onStage) => {
  await onStage("open");
  const images = blocks.filter((block) => block.type === "image");
  for (const image of images) await readPhoto(input, image.file, env[ATTACHMENT_DIR_ENV]);
  await Bun.sleep(800);
  return {
    state: {
      title: input.title,
      photos: images.length,
      fitted_photos: images.length,
      stickers: 0,
      maps: 0,
      category: input.category,
      tags: input.tags,
      saved_count: 1,
    },
    savedBefore: 0,
    savedAfter: 1,
  };
};

/** 덮어쓰기 대역. 브라우저에 닿지 않고 잠깐 기다린 뒤 사본 하나를 남기고 고친 것처럼 성공한다. */
export const fakeRunOverwrite: typeof runOverwrite = async (_env, input, onStage) => {
  await onStage("open");
  await Bun.sleep(800);
  return {
    draft_id: input.draft_id,
    backup_draft_id: "1",
    backup_title: "[덮어쓰기 전 원본] x",
    saved_before: 1,
    saved_after: 1,
    state: null,
  };
};

/**
 * 시험 전용 작업 프로세스 진입 파일. `ServerDeps.workerEntry` 로 넘기면 서버가 이 파일을
 * `--worker <작업 파일>` 로 띄우고, 같은 `runWorker` 를 가짜 `runDraft` 와 `runOverwrite` 로 돌린다.
 */
if (import.meta.main) {
  const index = process.argv.indexOf("--worker");
  const jobFile = index >= 0 ? process.argv[index + 1] : undefined;
  if (jobFile) await runWorker(jobFile, { runDraft: fakeRunDraft, runOverwrite: fakeRunOverwrite });
  process.exit(0);
}
