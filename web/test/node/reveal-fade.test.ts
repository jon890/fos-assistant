import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "../../node_modules/react/index.js";
import { renderToStaticMarkup } from "../../node_modules/react-dom/server.js";
import ReactMarkdown from "../../node_modules/react-markdown/index.js";
import {
  rehypeRevealFade,
  type TailFade,
} from "../../src/components/chat/reveal-fade.ts";

/** 보인 글 `text` 에서 `from` 부터 `to` 까지가 새로 보인 글일 때의 마크다운 결과다. */
function render(text: string, from: number, to: number, progress = 0.2) {
  const fades: TailFade[] = [
    {
      startFromEnd: text.length - from,
      endFromEnd: text.length - to,
      progress,
    },
  ];
  return renderToStaticMarkup(
    createElement(
      ReactMarkdown,
      {
        rehypePlugins: [
          [rehypeRevealFade, { fades, sourceLength: text.length }],
        ],
      },
      text,
    ),
  );
}

test("새로 보인 낱말만 투명도를 준 span 으로 감싼다", () => {
  const text = "앞 문장은 그대로 이어서 새 낱말 ";
  const html = render(text, text.indexOf("새"), text.length);
  assert.match(html, /^<p>앞 문장은 그대로 이어서 <span style="[^"]*opacity:0\.\d+[^"]*">새 낱말<\/span><\/p>$/);
});

test("이미 또렷해진 글에는 span 을 두지 않는다", () => {
  const html = renderToStaticMarkup(
    createElement(
      ReactMarkdown,
      { rehypePlugins: [[rehypeRevealFade, { fades: [], sourceLength: 5 }]] },
      "가나다 라",
    ),
  );
  assert.equal(html, "<p>가나다 라</p>");
});

test("굵은 글씨와 목록 안의 새 낱말도 글 끝에서 센 위치로 찾는다", () => {
  const text = "- 첫째 항목\n- 둘째 **굵은 말** 끝 ";
  const html = render(text, text.indexOf("끝"), text.length);
  assert.match(html, /<strong>굵은 말<\/strong> <span style="[^"]*">끝<\/span>/);
  assert.ok(html.includes("첫째 항목"));
});

test("코드 안의 글은 감싸지 않는다", () => {
  const text = "설명 `코드 조각` 입니다 ```\n";
  const html = render(text, 3, text.length - 1);
  assert.ok(html.includes("<code>코드 조각</code>"));
});

test("글 끝이 아닌 앞의 글은 건드리지 않는다", () => {
  const text = "하나 둘 셋 넷 ";
  const html = render(text, text.indexOf("셋"), text.length);
  assert.ok(html.startsWith("<p>하나 둘 <span"));
});
