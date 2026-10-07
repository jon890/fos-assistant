import { constants } from "node:fs";
import { lstat, open } from "node:fs/promises";
import { isAbsolute, join } from "node:path";
import { z } from "zod";
import { ToolError } from "./errors.ts";

/** `render_draft` 와 임시저장 도구가 함께 받는 다섯 칸. 길이와 개수는 `validateDraft` 가 문장으로 알린다. */
export const draftShape = {
  title: z.string().describe("제목. 1~100자"),
  category: z.string().describe("블로그에 이미 있는 카테고리 이름. 1~50자"),
  tags: z
    .array(z.string())
    .describe("태그 0~30개. 하나에 1~30자, # 과 쉼표 없이"),
  body: z
    .string()
    .describe(
      "본문. 20,000자까지. 한 줄이 한 문단이다. [사진 N: 파일 이름], [스티커: 코드], [지도: 상호명 | 주소] 줄은 구성요소가 된다",
    ),
  photo_dir: z
    .string()
    .optional()
    .describe("사진 지시가 있으면 필수. 대화 입력이 적은 첨부 사진 디렉터리의 절대 경로"),
};

export type DraftInput = {
  title: string;
  category: string;
  tags: string[];
  body: string;
  photo_dir?: string;
};

export type Block =
  | { type: "text"; line: string }
  | { type: "image"; number: number; file: string }
  | { type: "sticker"; code: string }
  | { type: "map"; name: string; address: string };

const IMAGE_LINE = /^\[사진 ([1-9][0-9]{0,2}): ([^/\]]+)\]$/;
const STICKER_LINE = /^\[스티커: ([A-Za-z0-9_-]{1,64})\]$/;
const MAP_LINE = /^\[지도: ([^|\]]+?) \| ([^\]]+)\]$/;
const PHOTO_NAME = /^[A-Za-z0-9._-]+\.(jpg|jpeg|png|webp|gif|heic)$/i;

const TITLE_MAX = 100;
const CATEGORY_MAX = 50;
const TAGS_MAX = 30;
const TAG_MAX = 30;
const BODY_MAX = 20_000;
const PHOTOS_MAX = 50;
const STICKERS_MAX = 30;
const MAPS_MAX = 30;
export const PHOTO_MAX_BYTES = 20 * 1024 * 1024;

const MIME: Record<string, string> = {
  jpg: "image/jpeg",
  jpeg: "image/jpeg",
  png: "image/png",
  gif: "image/gif",
  webp: "image/webp",
  heic: "image/heic",
};
const HEIC_BRANDS = new Set(["ftypheic", "ftypheix", "ftypmif1", "ftypmsf1"]);

const length = (value: string) => [...value].length;

/** 본문을 줄마다 구성요소로 나눈다. 지시 줄과 정확히 같지 않은 줄은 글 줄이다. */
export function parseBody(body: string): Block[] {
  return body.split("\n").map((raw): Block => {
    const line = raw.endsWith("\r") ? raw.slice(0, -1) : raw;
    const image = IMAGE_LINE.exec(line);
    if (image) return { type: "image", number: Number(image[1]), file: image[2]! };
    const sticker = STICKER_LINE.exec(line);
    if (sticker) return { type: "sticker", code: sticker[1]! };
    const map = MAP_LINE.exec(line);
    if (map) return { type: "map", name: map[1]!, address: map[2]! };
    return { type: "text", line };
  });
}

const isPhotoName = (file: string) =>
  PHOTO_NAME.test(file) && !file.includes("..");

const extensionOf = (file: string) =>
  file.slice(file.lastIndexOf(".") + 1).toLowerCase();

/** 칸의 모양과 길이, 구성요소 수, 사진 파일 이름을 검사해 어긋난 자리를 문장으로 돌려준다. */
export function validateDraft(input: DraftInput): string[] {
  const problems: string[] = [];
  const titleLength = length(input.title);
  if (titleLength < 1 || titleLength > TITLE_MAX)
    problems.push(`제목은 1자에서 ${TITLE_MAX}자까지입니다. 지금 ${titleLength}자입니다.`);
  const categoryLength = length(input.category);
  if (categoryLength < 1 || categoryLength > CATEGORY_MAX)
    problems.push(
      `카테고리는 1자에서 ${CATEGORY_MAX}자까지입니다. 지금 ${categoryLength}자입니다.`,
    );
  if (input.tags.length > TAGS_MAX)
    problems.push(`태그는 ${TAGS_MAX}개까지입니다. 지금 ${input.tags.length}개입니다.`);
  input.tags.forEach((tag, index) => {
    const tagLength = length(tag);
    if (tagLength < 1 || tagLength > TAG_MAX)
      problems.push(`${index + 1}번째 태그는 1자에서 ${TAG_MAX}자까지입니다.`);
    if (/[#,]/.test(tag))
      problems.push(`${index + 1}번째 태그에 # 이나 쉼표를 넣지 않습니다.`);
  });
  const bodyLength = length(input.body);
  if (bodyLength > BODY_MAX)
    problems.push(`본문은 ${BODY_MAX}자까지입니다. 지금 ${bodyLength}자입니다.`);

  const blocks = parseBody(input.body);
  const images = blocks.filter((block) => block.type === "image");
  const stickers = blocks.filter((block) => block.type === "sticker").length;
  const maps = blocks.filter((block) => block.type === "map").length;
  if (images.length > PHOTOS_MAX)
    problems.push(`사진은 ${PHOTOS_MAX}장까지입니다. 지금 ${images.length}장입니다.`);
  if (stickers > STICKERS_MAX)
    problems.push(`스티커는 ${STICKERS_MAX}개까지입니다. 지금 ${stickers}개입니다.`);
  if (maps > MAPS_MAX)
    problems.push(`지도는 ${MAPS_MAX}개까지입니다. 지금 ${maps}개입니다.`);
  for (const image of images) {
    if (!isPhotoName(image.file))
      problems.push(
        `사진 ${image.number}의 파일 이름 ${image.file} 은 받지 않습니다. jpg, jpeg, png, webp, gif, heic 파일 이름만 받습니다.`,
      );
  }
  if (images.length > 0 && !input.photo_dir)
    problems.push("사진 지시가 있으면 photo_dir 이 필요합니다.");
  return problems;
}

/** 파일 머리 바이트가 확장자의 이미지 서명과 맞는지 본다. */
function matchesSignature(extension: string, head: Uint8Array) {
  const ascii = (from: number, to: number) =>
    String.fromCharCode(...head.subarray(from, to));
  switch (extension) {
    case "jpg":
    case "jpeg":
      return head[0] === 0xff && head[1] === 0xd8 && head[2] === 0xff;
    case "png":
      return (
        head[0] === 0x89 && head[1] === 0x50 && head[2] === 0x4e && head[3] === 0x47
      );
    case "gif":
      return ascii(0, 4) === "GIF8";
    case "webp":
      return ascii(0, 4) === "RIFF" && ascii(8, 12) === "WEBP";
    case "heic":
      return HEIC_BRANDS.has(ascii(4, 12));
    default:
      return false;
  }
}

/** 사진 디렉터리가 링크 아닌 절대 경로 디렉터리인지 본다. 문장에 경로를 싣지 않는다. */
async function checkDirectory(photoDir: string): Promise<string | null> {
  if (!isAbsolute(photoDir) || photoDir.split("/").includes(".."))
    return "photo_dir 은 .. 없는 절대 경로여야 합니다.";
  try {
    const stat = await lstat(photoDir);
    if (stat.isSymbolicLink() || !stat.isDirectory())
      return "photo_dir 이 링크 아닌 디렉터리가 아닙니다.";
  } catch {
    return "photo_dir 디렉터리를 찾지 못했습니다.";
  }
  return null;
}

/**
 * 사진 파일 하나를 열어 보통 파일, 크기, 이미지 서명을 확인한다.
 * `whole` 이 참이면 바이트 전체를, 아니면 머리 12바이트를 돌려준다. 문제는 파일 이름만 담은 문장이다.
 */
async function inspectPhoto(
  photoDir: string,
  file: string,
  whole: boolean,
): Promise<{ problem: string } | { bytes: Uint8Array }> {
  if (!isPhotoName(file))
    return { problem: `사진 파일 이름 ${file} 은 받지 않습니다.` };
  const path = join(photoDir, file);
  try {
    const stat = await lstat(path);
    if (stat.isSymbolicLink() || !stat.isFile())
      return { problem: `사진 ${file} 은 링크 아닌 보통 파일이 아닙니다.` };
  } catch {
    return { problem: `사진 ${file} 을 찾지 못했습니다.` };
  }
  let handle;
  try {
    // 확인과 읽기 사이에 링크로 바뀌어도 따라가지 않는다.
    handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  } catch {
    return { problem: `사진 ${file} 을 열지 못했습니다.` };
  }
  try {
    const stat = await handle.stat();
    if (!stat.isFile())
      return { problem: `사진 ${file} 은 링크 아닌 보통 파일이 아닙니다.` };
    if (stat.size > PHOTO_MAX_BYTES)
      return { problem: `사진 ${file} 이 20MB 를 넘습니다.` };
    const size = whole ? stat.size : Math.min(12, stat.size);
    const bytes = new Uint8Array(size);
    const { bytesRead } = await handle.read(bytes, 0, size, 0);
    if (!matchesSignature(extensionOf(file), bytes.subarray(0, bytesRead)))
      return { problem: `사진 ${file} 의 내용이 확장자의 이미지 형식과 맞지 않습니다.` };
    return { bytes: bytes.subarray(0, bytesRead) };
  } finally {
    await handle.close();
  }
}

/** 본문이 부르는 사진 파일을 모두 확인한다. 같은 파일은 한 번만 본다. */
export async function checkPhotoFiles(input: DraftInput): Promise<string[]> {
  const files = [
    ...new Set(
      parseBody(input.body).flatMap((block) =>
        block.type === "image" ? [block.file] : [],
      ),
    ),
  ];
  if (files.length === 0) return [];
  if (!input.photo_dir) return ["사진 지시가 있으면 photo_dir 이 필요합니다."];
  const directoryProblem = await checkDirectory(input.photo_dir);
  if (directoryProblem) return [directoryProblem];
  const problems: string[] = [];
  for (const file of files) {
    const result = await inspectPhoto(input.photo_dir, file, false);
    if ("problem" in result) problems.push(result.problem);
  }
  return problems;
}

/** 같은 확인을 다시 한 뒤 사진 바이트와 MIME 형식을 돌려준다. 어긋나면 `NAVER_BLOG_PHOTO_INVALID` 다. */
export async function readPhoto(
  input: DraftInput,
  file: string,
): Promise<{ bytes: Uint8Array; mime: string }> {
  if (!input.photo_dir || (await checkDirectory(input.photo_dir)))
    throw new ToolError("NAVER_BLOG_PHOTO_INVALID");
  const result = await inspectPhoto(input.photo_dir, file, true);
  if ("problem" in result) throw new ToolError("NAVER_BLOG_PHOTO_INVALID");
  return { bytes: result.bytes, mime: MIME[extensionOf(file)]! };
}
