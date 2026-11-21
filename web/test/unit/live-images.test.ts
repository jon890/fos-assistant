import assert from "node:assert/strict";
import test from "node:test";
import {
  parseLiveImages,
  unboundLiveImages,
  withLiveImages,
} from "../../src/components/chat/artifact/live-images.ts";

test("묶이지 않은 그림 응답은 경로 배열만 받고 꼴이 다르면 null 이다", () => {
  assert.deepEqual(parseLiveImages(["generated/고양이.png", 3]), [
    "generated/고양이.png",
  ]);
  assert.equal(parseLiveImages({ paths: [] }), null);
});

test("이력의 답에 묶인 그림은 임시 그림에서 빠지고 남은 그림은 저장한 순서를 지킨다", () => {
  const turns = [
    {
      artifacts: [
        {
          path: "generated/고양이.png",
          byteSize: 1,
          deleted: false,
          kind: "IMAGE" as const,
        },
      ],
    },
    {},
  ];
  assert.deepEqual(
    unboundLiveImages(
      ["generated/고양이.png", "generated/강아지.png", "generated/새.png"],
      turns,
    ),
    ["generated/강아지.png", "generated/새.png"],
  );
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
