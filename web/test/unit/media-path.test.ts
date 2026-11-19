import assert from "node:assert/strict";
import { test } from "node:test";
import { stripMediaPaths } from "../../src/lib/media-path.ts";

test("MEDIA 태그와 그 경로를 뺀다", () => {
  assert.equal(
    stripMediaPaths("사진을 만들었습니다.\n\nMEDIA:/srv/x/.hermes/profiles/p/cache/generated/images/a.png"),
    "사진을 만들었습니다.",
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

test("낱말의 일부인 MEDIA 와 웹 주소의 캐시 경로, 백틱은 건드리지 않는다", () => {
  assert.equal(stripMediaPaths("SOCIAL_MEDIA:on 설정"), "SOCIAL_MEDIA:on 설정");
  assert.equal(stripMediaPaths("MEDIA:on 처럼 경로가 아닌 값"), "MEDIA:on 처럼 경로가 아닌 값");
  const url = "https://example.com/cache/generated/images/a.png 를 봐";
  assert.equal(stripMediaPaths(url), url);
  assert.equal(stripMediaPaths("경로는 `MEDIA:/srv/x/a.png` 다"), "경로는 `` 다");
});

test("집 폴더 표기와 폴더 이름으로 바로 시작하는 캐시 경로도 뺀다", () => {
  assert.equal(stripMediaPaths("파일 ~/.hermes/cache/generated/images/a.png 끝"), "파일 끝");
  assert.equal(stripMediaPaths("파일 /cache/generated/images/a.png 끝"), "파일 끝");
});
