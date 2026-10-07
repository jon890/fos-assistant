import { z } from "zod";
import {
  checkPhotoFiles,
  draftShape,
  parseBody,
  validateDraft,
  type Block,
  type DraftInput,
} from "./draft.ts";

/** `render_draft` 의 인자. 초안 다섯 칸에 미리보기만 쓰는 칸을 더한다. */
export const renderShape = {
  ...draftShape,
  photo_notes: z
    .record(z.string(), z.string())
    .optional()
    .describe("사진 번호를 키로 한 사진 설명. 미리보기에만 보인다. 값은 300자까지"),
  artifact_path: z
    .string()
    .describe(
      "결과물 폴더 안의 index.html 이나 <폴더>/index.html. 폴더는 세 단계까지. artifact_write 의 path 와 글자까지 같아야 한다",
    ),
  kind: z
    .enum(["preview", "package"])
    .default("preview")
    .describe("preview 는 모바일 미리보기, package 는 수동 등록용 묶음"),
};

export type RenderInput = DraftInput & {
  photo_notes?: Record<string, string>;
  artifact_path: string;
  kind?: "preview" | "package";
};

export type StickerAsset = { path: string; source_url: string };
export type RenderResult =
  | { problems: []; html: string; assets: StickerAsset[] }
  | { problems: string[]; html: null; assets: [] };

// 조각마다 점으로 시작하지 않고 `/` 와 `\\` 가 없다. 폴더는 세 단계까지다.
const ARTIFACT_PATH = /^(?:[^/\\.][^/\\]{0,80}\/){0,3}index\.html$/;
const NOTE_KEY = /^[1-9][0-9]{0,2}$/;
const NOTE_MAX = 300;
/** 같은 대화의 첨부 파일 이름 `<첨부 번호>.<확장자>`. 이 모양만 첨부 주소로 부른다. */
const ATTACHMENT_NAME = /^([0-9]+)\.[a-z0-9]+$/;
const OGQ_STICKER = /^(ogq_[0-9a-f]+)-([0-9]+)$/;
const STICKER_HOST = "https://storep-phinf.pstatic.net";

export function escapeHtml(value: string) {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function validateRenderOptions(input: RenderInput): string[] {
  const problems: string[] = [];
  if (!ARTIFACT_PATH.test(input.artifact_path))
    problems.push("artifact_path 는 index.html 이나 <폴더>/index.html 이고 폴더는 세 단계까지 받습니다.");
  for (const [key, note] of Object.entries(input.photo_notes ?? {})) {
    if (!NOTE_KEY.test(key))
      problems.push(`photo_notes 의 키 ${key} 는 사진 번호(1~999)여야 합니다.`);
    if ([...note].length > NOTE_MAX)
      problems.push(`photo_notes 의 ${key}번 설명은 ${NOTE_MAX}자까지입니다.`);
  }
  return problems;
}

/**
 * 초안을 검사하고 미리보기나 수동 등록용 HTML 을 만든다. 아무것도 쓰지 않는다.
 * 사진은 바인딩 주인의 첨부 디렉터리(`attachmentDir`) 아래에서만 받는다. 경로 문제는 원인을 나누지 않는다.
 */
export async function renderDraft(
  input: RenderInput,
  attachmentDir: string | undefined,
): Promise<RenderResult> {
  const problems = [...validateRenderOptions(input), ...validateDraft(input)];
  // 파일 이름이나 디렉터리 모양이 틀렸으면 그 경로의 파일을 열지 않는다.
  if (problems.length === 0) problems.push(...(await checkPhotoFiles(input, attachmentDir)));
  if (problems.length > 0) return { problems, html: null, assets: [] };

  const blocks = parseBody(input.body);
  const attachments = attachmentBase(input.artifact_path);
  if (input.kind === "package")
    return { problems: [], html: packageHtml(input, blocks, attachments), assets: [] };
  const folder = input.artifact_path.slice(0, -"index.html".length);
  return { problems: [], ...previewHtml(input, blocks, folder, attachments) };
}

/**
 * 결과물 HTML 에서 같은 대화의 첨부를 부르는 상대 주소.
 * 결과물은 대화 주소 아래 `files/<artifact_path>` 에 있고 첨부는 `files` 의 형제인 `attachments` 다.
 * 그래서 `index.html` 은 `../attachments`, `a/index.html` 은 `../../attachments` 다.
 */
export function attachmentBase(artifactPath: string) {
  const depth = artifactPath.split("/").length - 1;
  return `${"../".repeat(depth + 1)}attachments`;
}

/** 사진 자리. 첨부 이름이면 같은 대화의 첨부 주소를 상대 경로로 부르고, 아니면 이름표만 둔다. */
function photoHtml(
  block: Extract<Block, { type: "image" }>,
  attachments: string,
  note?: string,
) {
  const label = `${block.number}번째 사진`;
  const attachment = ATTACHMENT_NAME.exec(block.file);
  const image = attachment
    ? `<img src="${attachments}/${attachment[1]}" alt="${label}" loading="lazy">`
    : "";
  const caption = note
    ? `<span class="label">${label}</span> ${escapeHtml(note)}`
    : `<span class="label">${label}</span>`;
  return `<figure class="photo">${image}<figcaption>${caption}</figcaption></figure>`;
}

const PREVIEW_STYLE = `
*{box-sizing:border-box}
body{margin:0;background:#f2f3f5;color:#222;font-family:-apple-system,BlinkMacSystemFont,"Apple SD Gothic Neo","Malgun Gothic",sans-serif}
.screen{width:390px;max-width:100%;margin:0 auto;background:#fff;min-height:100vh;padding:20px 16px 40px}
.category{margin:0 0 6px;color:#03a94d;font-size:13px}
h1{margin:0 0 10px;font-size:22px;line-height:1.4;word-break:keep-all;overflow-wrap:anywhere}
.tags{margin:0 0 18px;color:#555;font-size:13px}
.tags span{margin-right:6px}
article p{margin:0;font-size:16px;line-height:1.8;white-space:pre-wrap;overflow-wrap:anywhere}
article p.blank{min-height:1.8em}
.photo{margin:12px -16px;width:calc(100% + 32px)}
.photo img{display:block;width:100%;height:auto;background:#e5e7eb;min-height:120px}
.photo figcaption{padding:6px 16px 0;color:#666;font-size:13px}
.photo .label,.placeholder{display:inline-block;padding:1px 6px;border-radius:4px;background:#eef1f4;color:#444;font-size:12px}
.sticker{display:block;width:min(100%, 370px);height:auto;margin:12px auto}
.placeholder{margin:12px 0}
.map{margin:12px 0;padding:12px 14px;border:1px solid #dfe3e8;border-radius:8px}
.map strong{display:block;font-size:15px}
.map span{color:#666;font-size:13px}
`;

function previewHtml(input: RenderInput, blocks: Block[], folder: string, attachments: string) {
  const assets: StickerAsset[] = [];
  const seenStickers = new Set<string>();
  const body = blocks
    .map((block) => {
      switch (block.type) {
        case "text":
          return block.line === ""
            ? `<p class="blank"></p>`
            : `<p>${escapeHtml(block.line)}</p>`;
        case "image":
          return photoHtml(block, attachments, input.photo_notes?.[String(block.number)]);
        case "sticker": {
          const ogq = OGQ_STICKER.exec(block.code);
          if (!ogq)
            return `<p class="placeholder">스티커: ${escapeHtml(block.code)}</p>`;
          if (!seenStickers.has(block.code)) {
            seenStickers.add(block.code);
            assets.push({
              path: `${folder}stickers/${block.code}.png`,
              source_url: `${STICKER_HOST}/${ogq[1]}/original_${ogq[2]}.png?type=p100_100`,
            });
          }
          // 결과물 화면은 외부 그림을 막으므로 에이전트가 같은 폴더에 받아 둔 그림을 부른다.
          return `<img class="sticker" src="stickers/${block.code}.png" alt="스티커 ${block.code}">`;
        }
        case "map":
          return `<div class="map"><strong>${escapeHtml(block.name)}</strong><span>${escapeHtml(block.address)}</span></div>`;
      }
    })
    .join("\n");
  const tags = input.tags
    .map((tag) => `<span>#${escapeHtml(tag)}</span>`)
    .join("");
  const html = `<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(input.title)}</title>
<style>${PREVIEW_STYLE}</style>
</head>
<body>
<main class="screen">
<p class="category">${escapeHtml(input.category)}</p>
<h1>${escapeHtml(input.title)}</h1>
<p class="tags">${tags}</p>
<article>
${body}
</article>
</main>
</body>
</html>
`;
  return { html, assets };
}

const PACKAGE_STYLE = `
*{box-sizing:border-box}
body{margin:0;background:#f2f3f5;color:#222;font-family:-apple-system,BlinkMacSystemFont,"Apple SD Gothic Neo","Malgun Gothic",sans-serif}
main{max-width:720px;margin:0 auto;background:#fff;padding:20px 16px 40px}
h1{font-size:20px;margin:0 0 12px}
h2{font-size:16px;margin:24px 0 8px}
ol{padding-left:20px;line-height:1.7}
pre{margin:0;padding:12px;background:#f6f8fa;border:1px solid #dfe3e8;border-radius:6px;white-space:pre-wrap;overflow-wrap:anywhere;font-family:inherit;font-size:15px;line-height:1.7}
.photo{margin:12px 0}
.photo img{display:block;width:100%;height:auto;background:#e5e7eb;min-height:120px}
.photo figcaption{padding-top:6px;color:#666;font-size:13px}
.photo .label{display:inline-block;padding:1px 6px;border-radius:4px;background:#eef1f4;color:#444;font-size:12px}
`;

/** 자동 입력이 막혔을 때 사람이 붙여넣을 묶음. 지시 줄은 자리 표시 글로 바꾼다. */
function packageHtml(input: RenderInput, blocks: Block[], attachments: string) {
  const text = blocks
    .map((block) => {
      switch (block.type) {
        case "text":
          return block.line;
        case "image":
          return `[${block.number}번째 사진 자리]`;
        case "sticker":
          return `[스티커 ${block.code}]`;
        case "map":
          return `[지도 ${block.name} / ${block.address}]`;
      }
    })
    .join("\n");
  const photos = blocks
    .flatMap((block) =>
      block.type === "image"
        ? [photoHtml(block, attachments, input.photo_notes?.[String(block.number)])]
        : [],
    )
    .join("\n");
  return `<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(input.title)}</title>
<style>${PACKAGE_STYLE}</style>
</head>
<body>
<main>
<h1>수동 등록용 묶음</h1>
<ol>
<li>네이버 블로그의 글쓰기 화면을 엽니다.</li>
<li>아래 제목을 제목 칸에 붙여넣습니다.</li>
<li>아래 본문을 본문 칸에 붙여넣습니다.</li>
<li>사진 자리마다 같은 번호의 사진을 올리고 「문서 너비」 를 고릅니다. 그다음 자리 표시 줄을 지웁니다.</li>
<li>스티커와 지도 자리에 그 스티커와 장소를 넣고 자리 표시 줄을 지웁니다.</li>
<li>발행 설정에서 카테고리와 태그를 넣고 「저장」 만 누릅니다. 발행하지 않습니다.</li>
</ol>
<h2>제목</h2>
<pre>${escapeHtml(input.title)}</pre>
<h2>카테고리</h2>
<pre>${escapeHtml(input.category)}</pre>
<h2>태그</h2>
<pre>${escapeHtml(input.tags.join(", "))}</pre>
<h2>본문</h2>
<pre>${escapeHtml(text)}</pre>
<h2>사진</h2>
${photos || "<p>사진이 없습니다.</p>"}
</main>
</body>
</html>
`;
}
