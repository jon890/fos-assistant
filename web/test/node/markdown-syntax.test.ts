// 한국어 글에서 강조 기호가 풀리는지 본다. 2026-11-21 운영 답에서 「**…‘보통’**으로」 의 별표가 그대로 보였다.
import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "../../node_modules/react/index.js";
import { renderToStaticMarkup } from "../../node_modules/react-dom/server.js";
import ReactMarkdown from "../../node_modules/react-markdown/index.js";
import { MARKDOWN_SYNTAX_PLUGINS } from "../../src/lib/markdown-syntax.ts";

function render(text: string): string {
  return renderToStaticMarkup(
    createElement(ReactMarkdown, { remarkPlugins: MARKDOWN_SYNTAX_PLUGINS }, text),
  );
}

test("닫는 ** 앞이 따옴표이고 뒤가 조사여도 굵게 보인다", () => {
  const html = render("**CJ올리브네트웍스는 팀 성장 ‘보통’**으로 판정했어요.");
  assert.equal(html, "<p><strong>CJ올리브네트웍스는 팀 성장 ‘보통’</strong>으로 판정했어요.</p>");
});

test("닫는 쪽 앞이 괄호나 마침표여도 굵게, 기울임, 취소선이 보인다", () => {
  assert.match(render("**이 괄호(parenthesis)**가 있다"), /<strong>이 괄호\(parenthesis\)<\/strong>가/);
  assert.match(render("**끝난 문장.**이라고 했다"), /<strong>끝난 문장\.<\/strong>이라고/);
  assert.match(render("*‘보통’*으로"), /<em>‘보통’<\/em>으로/);
  assert.match(render("~~‘보통’~~으로"), /<del>‘보통’<\/del>으로/);
  assert.match(render("~~이전 값(3)~~은 지웠다"), /<del>이전 값\(3\)<\/del>은/);
});

test("여는 쪽 앞이 한글이고 뒤가 따옴표나 괄호여도 굵게 보인다", () => {
  assert.match(render("결과는**‘보통’**이다"), /결과는<strong>‘보통’<\/strong>이다/);
  assert.match(render("값은**(3)**이다"), /값은<strong>\(3\)<\/strong>이다/);
});

test("물결 하나는 취소선으로 읽지 않는다", () => {
  assert.doesNotMatch(render("1~2시와 3~4시"), /<del>/);
});

test("영어 글의 강조 규칙은 그대로다", () => {
  assert.match(render("**bold** text and *em*"), /<strong>bold<\/strong> text and <em>em<\/em>/);
  assert.doesNotMatch(render('a**"b"**c'), /<strong>/);
});
