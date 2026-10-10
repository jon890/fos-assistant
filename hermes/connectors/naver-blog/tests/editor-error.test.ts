import { expect, test } from "bun:test";
import { EditorError as FromEditorError } from "../src/editor/editor-error.ts";
import { EditorError as FromPage } from "../src/editor/page.ts";

test("page.ts 가 다시 내보내는 EditorError 는 editor-error.ts 의 같은 클래스다", () => {
  expect(FromPage).toBe(FromEditorError);
});

test("EditorError 는 코드와 단계와 메시지와 덧붙은 값을 그대로 담는다", () => {
  const error = new FromPage("editor_failed", "open", "x", { a: 1 });
  expect(error).toBeInstanceOf(Error);
  expect(error.name).toBe("EditorError");
  expect(error.code).toBe("editor_failed");
  expect(error.stage).toBe("open");
  expect(error.message).toBe("x");
  expect(error.extra).toEqual({ a: 1 });
});

test("덧붙은 값을 주지 않으면 extra 는 빈 객체다", () => {
  expect(new FromEditorError("save_unconfirmed", "settings", "y").extra).toEqual({});
});
