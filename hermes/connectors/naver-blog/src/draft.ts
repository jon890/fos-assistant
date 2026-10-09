import { constants, type Stats } from "node:fs";
import { lstat, open, realpath } from "node:fs/promises";
import { isAbsolute, join } from "node:path";
import { z } from "zod";
import { EXISTING_LINE } from "./document.ts";
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
  // 네이버는 같은 태그를 한 번만 둔다. 대소문자와 앞뒤 공백만 다른 태그도 같은 태그다.
  const firstIndex = new Map<string, number>();
  input.tags.forEach((tag, index) => {
    const key = tag.trim().toLowerCase();
    if (!key) return;
    const first = firstIndex.get(key);
    if (first === undefined) firstIndex.set(key, index);
    else
      problems.push(
        `${index + 1}번째 태그가 ${first + 1}번째 태그와 같습니다. 같은 태그는 한 번만 넣습니다.`,
      );
  });
  const bodyLength = length(input.body);
  if (bodyLength > BODY_MAX)
    problems.push(`본문은 ${BODY_MAX}자까지입니다. 지금 ${bodyLength}자입니다.`);

  // `read_draft` 가 돌려준 기존 구성요소 줄은 그 글 안에서만 뜻이 있다. 새 글에 넣으면 글자로 들어간다.
  const existing = input.body.split("\n").filter((raw) => EXISTING_LINE.test(raw.replace(/\r$/, "")));
  if (existing.length)
    problems.push(
      `${existing[0]} 같은 기존 구성요소 줄은 새 글에 넣을 수 없습니다. 그 줄을 지우거나 [사진 N: 파일 이름] 으로 바꿉니다.`,
    );

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

/** 설치가 바인딩 주인의 첨부 디렉터리를 넣는 env 이름. connector.json 의 `owner_attachments_env` 와 같다. */
export const ATTACHMENT_DIR_ENV = "NAVER_BLOG_ATTACHMENT_DIR";

/**
 * 사진 경로의 문제는 원인과 상관없이 같은 문장이다. 없음, 링크, 첨부 디렉터리 밖, 크기, 서명을 나누면
 * 승인 없는 `render_draft` 로 다른 경로가 있는지 떠볼 수 있다(ADR-20261007 connector-owner-attachments). 문장에 경로를 싣지 않는다.
 */
export const PHOTO_DIRECTORY_PROBLEM = "사진 디렉터리를 쓸 수 없습니다.";
export const photoProblem = (number: number) =>
  `${number}번째 사진 자리의 사진을 쓸 수 없습니다.`;

/** `..` 조각 없는 절대 경로를 끝의 `/` 없이 돌려준다. 아니면 `null`. */
function plainAbsolute(path: string) {
  if (!isAbsolute(path) || path.split("/").includes("..") || path.includes("\0")) return null;
  const trimmed = path.replace(/\/+$/, "");
  return trimmed === "" ? null : trimmed;
}

/**
 * 사진 디렉터리가 바인딩 주인의 첨부 디렉터리(`attachmentDir`) 아래인지 본다. 맞으면 그 디렉터리의 경로다.
 * 첨부 디렉터리가 비었으면 아무 디렉터리도 받지 않는다. 등록 화면의 확인 도구에서는 비어 있다.
 * 첨부 디렉터리와 사진 디렉터리의 실제 경로가 받은 글자 그대로여야 하고,
 * 첨부 디렉터리부터 사진 디렉터리까지 조각마다 `lstat` 으로 링크 아닌 디렉터리여야 한다.
 * 대시보드 plugin 이 첨부 디렉터리를 설치할 때 쓰는 규칙과 같다.
 */
async function checkDirectory(
  photoDir: string,
  attachmentDir: string | undefined,
): Promise<string | null> {
  const root = plainAbsolute(attachmentDir ?? "");
  const directory = plainAbsolute(photoDir);
  if (!root || !directory) return null;
  if (directory !== root && !directory.startsWith(`${root}/`)) return null;
  try {
    // 실제 경로가 글자 그대로면 `.` 이나 빈 조각도 없다.
    if ((await realpath(root)) !== root || (await realpath(directory)) !== directory) return null;
    const paths = [root];
    if (directory !== root)
      for (const part of directory.slice(root.length + 1).split("/"))
        paths.push(`${paths[paths.length - 1]}/${part}`);
    for (const path of paths) {
      const stat = await lstat(path);
      if (stat.isSymbolicLink() || !stat.isDirectory()) return null;
    }
  } catch {
    return null;
  }
  return directory;
}

/**
 * 사진 파일을 연 뒤 디렉터리 확인을 다시 하고, 그 자리의 파일이 연 파일(`opened`)과 같은지 `lstat` 으로 대조한다.
 * `O_NOFOLLOW` 는 마지막 조각만 막으므로, 확인과 열기 사이에 위 디렉터리가 링크로 바뀌면 연 파일이 밖의 것일 수 있다.
 * 열린 뒤에도 같은 경로가 첨부 디렉터리 아래의 같은 파일을 가리키면 연 파일이 그 파일이다.
 * 실행 공간이 첨부를 읽기 전용으로 붙인다는 전제에 기대지 않는다.
 */
export async function stillInPlace(
  photoDir: string,
  attachmentDir: string | undefined,
  path: string,
  opened: Stats,
): Promise<boolean> {
  if ((await checkDirectory(photoDir, attachmentDir)) === null) return false;
  try {
    const again = await lstat(path);
    return (
      !again.isSymbolicLink() && again.isFile() && again.dev === opened.dev && again.ino === opened.ino
    );
  } catch {
    return false;
  }
}

/**
 * 사진 파일 하나를 열어 보통 파일, 크기, 이미지 서명을 확인한다.
 * `whole` 이 참이면 바이트 전체를, 아니면 머리 12바이트를 돌려준다. 어긋나면 `null` 이다.
 */
async function inspectPhoto(
  photoDir: string,
  attachmentDir: string | undefined,
  directory: string,
  file: string,
  whole: boolean,
): Promise<Uint8Array | null> {
  if (!isPhotoName(file)) return null;
  const path = join(directory, file);
  let linked;
  try {
    linked = await lstat(path);
  } catch {
    return null;
  }
  if (linked.isSymbolicLink() || !linked.isFile()) return null;
  let handle;
  try {
    // 확인과 읽기 사이에 링크로 바뀌어도 따라가지 않는다.
    handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  } catch {
    return null;
  }
  try {
    const stat = await handle.stat();
    // 확인한 파일과 연 파일이 다르면 그 사이에 바뀐 것이다.
    if (!stat.isFile() || stat.dev !== linked.dev || stat.ino !== linked.ino) return null;
    if (!(await stillInPlace(photoDir, attachmentDir, path, stat))) return null;
    if (stat.size > PHOTO_MAX_BYTES) return null;
    const size = whole ? stat.size : Math.min(12, stat.size);
    const bytes = new Uint8Array(size);
    const { bytesRead } = await handle.read(bytes, 0, size, 0);
    if (!matchesSignature(extensionOf(file), bytes.subarray(0, bytesRead))) return null;
    return bytes.subarray(0, bytesRead);
  } catch {
    return null;
  } finally {
    await handle.close();
  }
}

/**
 * 본문이 부르는 사진 파일을 모두 확인한다. 같은 파일은 한 번만 열고, 문제는 그 파일을 부른 사진 자리마다 알린다.
 * `attachmentDir` 은 바인딩 설치가 넣은 주인의 첨부 디렉터리다. 비었으면 사진을 하나도 받지 않는다.
 */
export async function checkPhotoFiles(
  input: DraftInput,
  attachmentDir: string | undefined,
): Promise<string[]> {
  const images = parseBody(input.body).flatMap((block) =>
    block.type === "image" ? [block] : [],
  );
  if (images.length === 0) return [];
  if (!input.photo_dir) return ["사진 지시가 있으면 photo_dir 이 필요합니다."];
  const directory = await checkDirectory(input.photo_dir, attachmentDir);
  if (!directory) return [PHOTO_DIRECTORY_PROBLEM];
  const usable = new Map<string, boolean>();
  const problems: string[] = [];
  for (const image of images) {
    if (!usable.has(image.file))
      usable.set(
        image.file,
        (await inspectPhoto(input.photo_dir, attachmentDir, directory, image.file, false)) !== null,
      );
    if (!usable.get(image.file)) problems.push(photoProblem(image.number));
  }
  return problems;
}

/** 같은 확인을 다시 한 뒤 사진 바이트와 MIME 형식을 돌려준다. 어긋나면 `NAVER_BLOG_PHOTO_INVALID` 다. */
export async function readPhoto(
  input: DraftInput,
  file: string,
  attachmentDir: string | undefined,
): Promise<{ bytes: Uint8Array; mime: string }> {
  const photoDir = input.photo_dir;
  const directory = photoDir ? await checkDirectory(photoDir, attachmentDir) : null;
  const bytes =
    photoDir && directory
      ? await inspectPhoto(photoDir, attachmentDir, directory, file, true)
      : null;
  if (!bytes) throw new ToolError("NAVER_BLOG_PHOTO_INVALID");
  return { bytes, mime: MIME[extensionOf(file)]! };
}
