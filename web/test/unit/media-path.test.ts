import assert from "node:assert/strict";
import { test } from "node:test";
import { stripMediaPaths } from "../../src/lib/media-path.ts";

test("MEDIA 태그와 그 경로를 뺀다", () => {
  assert.equal(
    stripMediaPaths("사진을 만들었습니다.\n\nMEDIA:/srv/x/.hermes/profiles/p/cache/generated/images/a.png"),
    "사진을 만들었습니다.\n\n",
  );
  assert.equal(stripMediaPaths("만드는 중 MEDIA:/srv/x/ca"), "만드는 중");
});

test("태그 없이 적은 Hermes 이미지 캐시 경로를 뺀다", () => {
  assert.equal(stripMediaPaths("그림은 /srv/x/cache/generated/images/a.png 에 있어요."), "그림은 에 있어요.");
});

test("경로가 없는 글과 보통 주소는 그대로 둔다", () => {
  const text = "그림 주소는 https://example.com/cache/a.png 입니다.";
  assert.equal(stripMediaPaths(text), text);
});
