import type { Options } from "react-markdown";
import remarkCjkFriendly from "remark-cjk-friendly/parseOnly";
import remarkCjkFriendlyGfmStrikethrough from "remark-cjk-friendly-gfm-strikethrough/parseOnly";
import remarkGfm from "remark-gfm";

/**
 * 화면의 마크다운이 문법을 읽는 remark 플러그인이다. 채팅 답과 개발 문서가 함께 쓴다.
 *
 * CommonMark 는 닫는 `**` 바로 앞이 문장 부호(`’`, `)`, `.`)이고 바로 뒤가 글자면 강조를 닫지 않는다.
 * 한국어는 조사가 띄어쓰기 없이 붙어(`**‘보통’**으로`) 별표가 그대로 보인다.
 * `remark-cjk-friendly` 가 굵게와 기울임을, `-gfm-strikethrough` 가 취소선을 한중일 글에 맞게 읽는다.
 * 취소선 플러그인은 GFM 의 취소선을 바꾸므로 `remark-gfm` 뒤에 둔다.
 * 물결 하나(`1~2시`)는 취소선으로 읽지 않는다.
 */
export const MARKDOWN_SYNTAX_PLUGINS: NonNullable<Options["remarkPlugins"]> = [
  [remarkGfm, { singleTilde: false }],
  remarkCjkFriendly,
  [remarkCjkFriendlyGfmStrikethrough, { singleTilde: false }],
];
