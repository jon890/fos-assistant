import assert from "node:assert/strict";
import test from "node:test";
import {
  receiveLiveImage,
  withLiveImages,
} from "../../src/components/chat/artifact/live-images.ts";

test("image 사건의 경로는 받은 순서대로 쌓이고 같은 경로는 한 번만 둔다", () => {
  let paths: string[] = [];
  paths = receiveLiveImage(paths, "generated/고양이.png");
  paths = receiveLiveImage(paths, "generated/강아지.png");
  paths = receiveLiveImage(paths, "generated/고양이.png");
  assert.deepEqual(paths, ["generated/고양이.png", "generated/강아지.png"]);
});

test("임시 그림은 답에 든 그림 뒤에 그림 종류로 붙고 이미 든 경로는 다시 넣지 않는다", () => {
  const saved = {
    path: "generated/고양이.png",
    byteSize: 10,
    deleted: false,
    kind: "IMAGE" as const,
    prompt: "지시문",
  };
  const merged = withLiveImages(
    [saved],
    ["generated/고양이.png", "generated/강아지.png"],
  );
  assert.deepEqual(merged, [
    saved,
    {
      path: "generated/강아지.png",
      byteSize: 0,
      deleted: false,
      kind: "IMAGE",
    },
  ]);
});
