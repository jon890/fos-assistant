import { ATTACHMENT_DIR_ENV, readPhoto } from "../src/draft.ts";
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

/**
 * 시험 전용 작업 프로세스 진입 파일. `ServerDeps.workerEntry` 로 넘기면 서버가 이 파일을
 * `--worker <작업 파일>` 로 띄우고, 같은 `runWorker` 를 가짜 `runDraft` 로 돌린다.
 */
if (import.meta.main) {
  const index = process.argv.indexOf("--worker");
  const jobFile = index >= 0 ? process.argv[index + 1] : undefined;
  if (jobFile) await runWorker(jobFile, { runDraft: fakeRunDraft });
  process.exit(0);
}
