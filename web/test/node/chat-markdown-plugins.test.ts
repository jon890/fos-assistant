// 채팅 마크다운 플러그인의 판정이다. 기준은 docs/features/answer-quality/spec.md 의 REQ17부터 REQ19 다.
import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "../../node_modules/react/index.js";
import { renderToStaticMarkup } from "../../node_modules/react-dom/server.js";
import ReactMarkdown from "../../node_modules/react-markdown/index.js";
import {
  isOpenFence,
  rehypeSoftBreaks,
  rehypeSourceSection,
  remarkOpenFence,
} from "../../src/components/chat/markdown-plugins.ts";
import { MARKDOWN_SYNTAX_PLUGINS } from "../../src/lib/markdown-syntax.ts";
import { rehypeRevealFade } from "../../src/components/chat/reveal-fade.ts";

function render(text: string, streaming = false): string {
  return renderToStaticMarkup(
    createElement(
      ReactMarkdown,
      {
        remarkPlugins: [...MARKDOWN_SYNTAX_PLUGINS, [remarkOpenFence, { streaming }]],
        rehypePlugins: [rehypeSourceSection, rehypeSoftBreaks],
      },
      text,
    ),
  );
}

// 2026-11-20 운영 답의 끝 출처 블록 모양이다. 줄바꿈 한 번으로 이어져 한 문단이 됐다.
const SOURCE_BLOCK = [
  "출처",
  "NHS, 소아 발열 안내: https://www.nhs.uk/symptoms/fever-in-children/",
  "NHS, 소아 경련 응급처치: https://healthiertogether.nhs.uk/child-under-12-years/seizure",
].join("\n");

test("줄바꿈 한 번을 br 로 보인다", () => {
  const html = render(SOURCE_BLOCK);
  assert.equal(html.match(/<br\/>/g)?.length, 2);
  assert.match(html, /^<p>출처<br\/>NHS, 소아 발열 안내/);
});

test("코드 블록 안의 줄바꿈과 블록 사이는 그대로 둔다", () => {
  const html = render("```\na\nb\n```\n\n- 하나\n- 둘\n\n> 인용\n\n| 가 | 나 |\n| --- | --- |\n| 1 | 2 |");
  assert.match(html, /<code>a\nb\n<\/code>/);
  assert.doesNotMatch(html, /<br\/>/);
});

test("목록 항목 안의 줄바꿈은 br 이고 하위 목록 앞에는 br 을 두지 않는다", () => {
  const html = render("- 첫 줄\n  둘째 줄\n  - 하위");
  assert.match(html, /첫 줄<br\/>둘째 줄<ul>/);
});

test("글이 「출처」 인 제목부터 다음 제목 앞까지를 출처 절로 감싼다", () => {
  const html = render("본문\n\n### 출처\n\n- [NHS · 소아 발열](https://www.nhs.uk/a)\n\n## 다음");
  assert.match(html, /<section class="answer-sources"><h3>출처<\/h3>\n?<ul>[\s\S]*<\/ul>\n?<\/section>\n?<h2>다음<\/h2>/);
});

test("굵은 글자 「출처」 줄과 「출처」 가 아닌 제목은 감싸지 않는다", () => {
  assert.doesNotMatch(render("**출처**\n\n- a"), /section/);
  assert.doesNotMatch(render("## 출처와 참고\n\n- a"), /section/);
  assert.match(render("## 출처:\n\n- a"), /answer-sources/);
});

test("흐림 효과와 함께 써도 출처 절과 줄바꿈이 남는다", () => {
  const text = "앞 줄\n뒤 줄\n\n### 출처\n\n- 하나";
  const html = renderToStaticMarkup(
    createElement(
      ReactMarkdown,
      {
        rehypePlugins: [
          rehypeSourceSection,
          [rehypeRevealFade, { fades: [{ startFromEnd: text.length, endFromEnd: 0, progress: 0.5 }], sourceLength: text.length }],
          rehypeSoftBreaks,
        ],
      },
      text,
    ),
  );
  assert.match(html, /answer-sources/);
  assert.match(html, /<br\/>/);
  assert.match(html, /opacity/);
});

test("흐르는 중에만 닫는 줄이 없는 코드 블록에 data-open 을 단다", () => {
  assert.doesNotMatch(render("```mermaid\nflowchart TD\n  A --> B"), /data-open/);
  assert.match(render("```mermaid\nflowchart TD\n  A --> B", true), /<code class="language-mermaid" data-open="true">/);
  assert.match(render("```mermaid\nflowchart TD\n```", true), /<code class="language-mermaid">/);
});

test("닫는 줄 판정은 여는 줄과 같은 글자로 같은 길이 이상일 때만 닫힌 것으로 본다", () => {
  assert.equal(isOpenFence("```mermaid\nA"), true);
  assert.equal(isOpenFence("```mermaid"), true);
  assert.equal(isOpenFence("```mermaid\nA\n```"), false);
  assert.equal(isOpenFence("````\nA\n```"), true);
  assert.equal(isOpenFence("~~~\nA\n```"), true);
  assert.equal(isOpenFence("    들여쓴 코드"), false);
});

test("흐림 구간의 경계가 줄바꿈에 걸려도 두 줄 사이의 br 이 남는다", () => {
  const text = "앞 줄\n뒤 줄";
  const boundary = text.indexOf("\n") + 1;
  const html = renderToStaticMarkup(
    createElement(
      ReactMarkdown,
      {
        rehypePlugins: [
          rehypeSourceSection,
          [
            rehypeRevealFade,
            {
              fades: [
                { startFromEnd: text.length, endFromEnd: text.length - boundary, progress: 0.3 },
                { startFromEnd: text.length - boundary, endFromEnd: 0, progress: 0.6 },
              ],
              sourceLength: text.length,
            },
          ],
          rehypeSoftBreaks,
        ],
      },
      text,
    ),
  );
  assert.match(html, /앞 줄<br\/><\/span><span[^>]*>뒤 줄/);
});
