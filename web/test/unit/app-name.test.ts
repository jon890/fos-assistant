import assert from "node:assert/strict";
import test from "node:test";
import { appName, DEFAULT_APP_NAME } from "../../web/src/lib/app-name.ts";

test("앱 이름은 실행할 때의 APP_NAME 을 읽고 비었으면 중립 기본값을 쓴다", () => {
  const before = process.env.APP_NAME;
  try {
    process.env.APP_NAME = "검사용 비서";
    assert.equal(appName(), "검사용 비서");
    process.env.APP_NAME = "   ";
    assert.equal(appName(), DEFAULT_APP_NAME);
    delete process.env.APP_NAME;
    assert.equal(appName(), "fos-assistant");
  } finally {
    if (before === undefined) delete process.env.APP_NAME;
    else process.env.APP_NAME = before;
  }
});
