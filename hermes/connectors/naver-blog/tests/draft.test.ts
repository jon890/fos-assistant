import { afterEach, beforeEach, expect, test } from "bun:test";
import { mkdir, mkdtemp, open, realpath, rename, rm, symlink, truncate, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  checkPhotoFiles,
  parseBody,
  PHOTO_DIRECTORY_PROBLEM,
  PHOTO_MAX_BYTES,
  photoProblem,
  readPhoto,
  stillInPlace,
  validateDraft,
  type DraftInput,
} from "../src/draft.ts";

const JPEG = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0, 0x10, 0x4a, 0x46, 0x49, 0x46, 0, 1]);
const PNG = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d]);
const WEBP = new TextEncoder().encode("RIFF\0\0\0\0WEBPVP8 ");
const HEIC = Uint8Array.from([0, 0, 0, 0x18, ...new TextEncoder().encode("ftypheic")]);

let root: string;
/** 바인딩 설치가 `NAVER_BLOG_ATTACHMENT_DIR` 로 주는 주인의 첨부 디렉터리. */
let attachmentDir: string;
/** 같은 첨부 루트 아래 다른 사용자의 디렉터리. */
let otherDir: string;
let photoDir: string;

beforeEach(async () => {
  // macOS 의 임시 디렉터리는 링크 아래에 있다. 커넥터가 링크를 거절하므로 실제 경로를 쓴다.
  root = await realpath(await mkdtemp(join(tmpdir(), "naver-blog-draft-")));
  attachmentDir = join(root, "users", "owner");
  otherDir = join(root, "users", "other");
  photoDir = join(attachmentDir, "photos");
  await mkdir(photoDir, { recursive: true });
  await mkdir(otherDir, { recursive: true });
});

afterEach(async () => {
  await rm(root, { recursive: true, force: true });
});

const draft = (body: string, extra: Partial<DraftInput> = {}): DraftInput => ({
  title: "동네 국숫집",
  category: "맛집",
  tags: ["가상국수", "국수"],
  body,
  photo_dir: photoDir,
  ...extra,
});

/** 문장에 사진 디렉터리 경로가 없는지 본다. */
function expectNoPath(problems: string[]) {
  for (const problem of problems) {
    expect(problem).not.toContain(root);
    expect(problem).not.toContain(tmpdir());
  }
}

test("지시 줄 셋과 글 줄, 빈 줄을 차례로 나누고 줄 끝 \\r 을 뗀다", () => {
  const blocks = parseBody(
    [
      "[스티커: sticker_hello]",
      "안녕하세요 가상블로그입니다\r",
      "",
      "[사진 1: 101.jpg]",
      "[지도: 가상국수 | 서울특별시 가상구 예시로 1]\r",
    ].join("\n"),
  );

  expect(blocks).toEqual([
    { type: "sticker", code: "sticker_hello" },
    { type: "text", line: "안녕하세요 가상블로그입니다" },
    { type: "text", line: "" },
    { type: "image", number: 1, file: "101.jpg" },
    { type: "map", name: "가상국수", address: "서울특별시 가상구 예시로 1" },
  ]);
});

test.each([
  "[사진 0: 101.jpg]",
  "[사진 1:101.jpg]",
  " [사진 1: 101.jpg]",
  "[사진 1: a/101.jpg]",
  "[스티커: 공백 있는 코드]",
  "[지도: 가상국수|서울특별시]",
])("모양이 틀린 지시 줄 %p 은 글 줄이 된다", (line) => {
  expect(parseBody(line)).toEqual([{ type: "text", line }]);
});

test("모양이 맞는 초안은 문장이 없다", () => {
  expect(validateDraft(draft("[사진 1: 101.jpg]\n본문"))).toEqual([]);
  expect(validateDraft(draft("사진 없는 글", { photo_dir: undefined, tags: [] }))).toEqual([]);
});

test.each([
  ["빈 제목", { title: "" }, "제목"],
  ["101자 제목", { title: "가".repeat(101) }, "제목"],
  ["51자 카테고리", { category: "가".repeat(51) }, "카테고리"],
  ["31개 태그", { tags: Array.from({ length: 31 }, (_, i) => `t${i}`) }, "태그는 30개"],
  ["# 이 든 태그", { tags: ["#국수"] }, "1번째 태그"],
  ["쉼표가 든 태그", { tags: ["국수,맛집"] }, "1번째 태그"],
  ["31자 태그", { tags: ["가".repeat(31)] }, "1번째 태그"],
  ["20,001자 본문", { body: "가".repeat(20_001) }, "본문"],
])("%s 는 문장으로 알린다", (_, extra, word) => {
  const problems = validateDraft(draft("본문", extra));

  expect(problems).toHaveLength(1);
  expect(problems[0]).toContain(word);
});

test("대소문자와 앞뒤 공백만 다른 태그가 두 번 오면 둘째 자리를 문장으로 알린다", () => {
  const problems = validateDraft(draft("본문", { tags: ["Noodle", "국수", " noodle "] }));

  expect(problems).toEqual([expect.stringContaining("3번째 태그가 1번째 태그와 같습니다")]);
});

test("20,000자 본문은 받는다", () => {
  expect(validateDraft(draft("가".repeat(20_000)))).toEqual([]);
});

test("사진 51장, 스티커와 지도 31개는 각각 문장으로 알린다", () => {
  const photos = Array.from({ length: 51 }, (_, i) => `[사진 ${i + 1}: ${i + 1}.jpg]`);
  const stickers = Array.from({ length: 31 }, () => "[스티커: sticker_hello]");
  const maps = Array.from({ length: 31 }, () => "[지도: 가상국수 | 서울특별시 가상구 예시로 1]");

  const problems = validateDraft(draft([...photos, ...stickers, ...maps].join("\n")));

  expect(problems).toEqual([
    expect.stringContaining("사진은 50장"),
    expect.stringContaining("스티커는 30개"),
    expect.stringContaining("지도는 30개"),
  ]);
});

test("사진 지시가 있는데 photo_dir 이 없으면 문장으로 알린다", () => {
  const problems = validateDraft(draft("[사진 1: 101.jpg]", { photo_dir: undefined }));

  expect(problems).toEqual([expect.stringContaining("photo_dir")]);
});

test.each(["..jpg", "a..b.jpg", "101.txt", "101.JPG.exe"])(
  "사진 파일 이름 %p 은 받지 않는다",
  (file) => {
    const problems = validateDraft(draft(`[사진 1: ${file}]`));

    expect(problems).toEqual([expect.stringContaining(file)]);
  },
);

test("확장자의 대소문자는 가리지 않는다", () => {
  expect(validateDraft(draft("[사진 1: 101.JPG]"))).toEqual([]);
});

test("첨부 디렉터리 아래의 서명이 맞는 사진 파일은 문장이 없고, 같은 파일을 두 번 놓아도 된다", async () => {
  await writeFile(join(photoDir, "101.jpg"), JPEG);
  await writeFile(join(photoDir, "102.png"), PNG);
  await writeFile(join(photoDir, "103.webp"), WEBP);
  await writeFile(join(photoDir, "104.heic"), HEIC);

  const problems = await checkPhotoFiles(
    draft("[사진 1: 101.jpg]\n[사진 2: 102.png]\n[사진 3: 103.webp]\n[사진 4: 104.heic]\n[사진 5: 101.jpg]"),
    attachmentDir,
  );

  expect(problems).toEqual([]);
});

test("첨부 디렉터리 바로 아래의 사진도 받는다", async () => {
  await writeFile(join(attachmentDir, "101.jpg"), JPEG);

  expect(await checkPhotoFiles(draft("[사진 1: 101.jpg]", { photo_dir: attachmentDir }), attachmentDir)).toEqual([]);
});

test("링크, 20MB 초과, 서명이 틀린 파일, 없는 파일을 사진 자리 번호만 담은 같은 문장으로 알린다", async () => {
  await writeFile(join(root, "target.jpg"), JPEG);
  await symlink(join(root, "target.jpg"), join(photoDir, "link.jpg"));
  await writeFile(join(photoDir, "big.jpg"), JPEG);
  await truncate(join(photoDir, "big.jpg"), PHOTO_MAX_BYTES + 1);
  await writeFile(join(photoDir, "fake.jpg"), PNG);

  const problems = await checkPhotoFiles(
    draft("[사진 1: link.jpg]\n[사진 2: big.jpg]\n[사진 3: fake.jpg]\n[사진 4: missing.jpg]"),
    attachmentDir,
  );

  expect(problems).toEqual([
    "1번째 사진 자리의 사진을 쓸 수 없습니다.",
    "2번째 사진 자리의 사진을 쓸 수 없습니다.",
    "3번째 사진 자리의 사진을 쓸 수 없습니다.",
    "4번째 사진 자리의 사진을 쓸 수 없습니다.",
  ]);
  expectNoPath(problems);
});

test("같은 문제 파일을 두 자리에 놓으면 두 자리를 모두 알린다", async () => {
  const problems = await checkPhotoFiles(
    draft("[사진 1: missing.jpg]\n[사진 7: missing.jpg]"),
    attachmentDir,
  );

  expect(problems).toEqual([photoProblem(1), photoProblem(7)]);
});

test("20MB 와 같은 크기의 사진은 받는다", async () => {
  await writeFile(join(photoDir, "edge.jpg"), JPEG);
  await truncate(join(photoDir, "edge.jpg"), PHOTO_MAX_BYTES);

  expect(await checkPhotoFiles(draft("[사진 1: edge.jpg]"), attachmentDir)).toEqual([]);
});

test("photo_dir 밖을 가리키는 ../x.jpg 는 사진 지시가 되지 않고 readPhoto 도 거절한다", async () => {
  await writeFile(join(attachmentDir, "x.jpg"), JPEG);
  // 지시 줄 정규식이 / 를 받지 않아 글 줄이 된다. 파일을 직접 읽는 쪽도 같은 이름을 거절한다.
  const input = draft("[사진 1: ../x.jpg]");

  expect(parseBody(input.body)).toEqual([{ type: "text", line: "[사진 1: ../x.jpg]" }]);
  await expect(readPhoto(input, "../x.jpg", attachmentDir)).rejects.toMatchObject({
    code: "NAVER_BLOG_PHOTO_INVALID",
  });
});

/** 사진 디렉터리의 문제마다 그 상황을 만들고 `[photo_dir, 첨부 디렉터리]` 를 돌려준다. */
const directoryCases: Array<[string, () => Promise<[string, string | undefined]>]> = [
  ["상대 경로", async () => ["photos", attachmentDir]],
  [".. 조각", async () => [`${photoDir}/../photos`, attachmentDir]],
  ["없는 디렉터리", async () => [join(attachmentDir, "missing"), attachmentDir]],
  [
    "다른 사용자의 디렉터리",
    async () => {
      await writeFile(join(otherDir, "101.jpg"), JPEG);
      return [otherDir, attachmentDir];
    },
  ],
  [
    "첨부 디렉터리 이름으로 시작하는 옆 디렉터리",
    async () => {
      await mkdir(`${attachmentDir}-x`);
      await writeFile(join(`${attachmentDir}-x`, "101.jpg"), JPEG);
      return [`${attachmentDir}-x`, attachmentDir];
    },
  ],
  [
    "다른 사용자의 디렉터리를 가리키는 링크",
    async () => {
      await writeFile(join(otherDir, "101.jpg"), JPEG);
      await symlink(otherDir, join(attachmentDir, "linked"));
      return [join(attachmentDir, "linked"), attachmentDir];
    },
  ],
  [
    "첨부 디렉터리 안을 가리키는 링크",
    async () => {
      await symlink(photoDir, join(attachmentDir, "linked"));
      return [join(attachmentDir, "linked"), attachmentDir];
    },
  ],
  [
    "중간 조각이 링크인 경로",
    async () => {
      await symlink(attachmentDir, join(attachmentDir, "loop"));
      return [join(attachmentDir, "loop", "photos"), attachmentDir];
    },
  ],
  [
    "첨부 디렉터리 자체가 링크",
    async () => {
      await symlink(attachmentDir, join(root, "users", "alias"));
      const alias = join(root, "users", "alias");
      return [join(alias, "photos"), alias];
    },
  ],
  ["빈 env", async () => [photoDir, ""]],
  ["없는 env", async () => [photoDir, undefined]],
];

test.each(directoryCases)("photo_dir 이 %s 이면 경로 없이 같은 문장으로 알린다", async (_, prepare) => {
  await writeFile(join(photoDir, "101.jpg"), JPEG);
  const [dir, attachments] = await prepare();

  const problems = await checkPhotoFiles(draft("[사진 1: 101.jpg]", { photo_dir: dir }), attachments);

  expect(problems).toEqual([PHOTO_DIRECTORY_PROBLEM]);
  expect(PHOTO_DIRECTORY_PROBLEM).toBe("사진 디렉터리를 쓸 수 없습니다.");
  expectNoPath(problems);
});

test.each(directoryCases)("readPhoto 는 photo_dir 이 %s 이면 NAVER_BLOG_PHOTO_INVALID 로 거절한다", async (_, prepare) => {
  await writeFile(join(photoDir, "101.jpg"), JPEG);
  const [dir, attachments] = await prepare();

  await expect(
    readPhoto(draft("[사진 1: 101.jpg]", { photo_dir: dir }), "101.jpg", attachments),
  ).rejects.toMatchObject({ code: "NAVER_BLOG_PHOTO_INVALID" });
});

test("readPhoto 는 확인한 사진의 바이트와 MIME 형식을 돌려준다", async () => {
  await writeFile(join(photoDir, "102.png"), PNG);

  const photo = await readPhoto(draft("[사진 1: 102.png]"), "102.png", attachmentDir);

  expect(photo.mime).toBe("image/png");
  expect([...photo.bytes]).toEqual([...PNG]);
});

test("readPhoto 는 서명이 틀린 사진을 NAVER_BLOG_PHOTO_INVALID 로 거절한다", async () => {
  await writeFile(join(photoDir, "fake.jpg"), PNG);

  await expect(readPhoto(draft("[사진 1: fake.jpg]"), "fake.jpg", attachmentDir)).rejects.toMatchObject({
    code: "NAVER_BLOG_PHOTO_INVALID",
  });
});

test("연 뒤의 재확인은 그 자리가 연 파일 그대로일 때만 통과하고, 파일이나 위 디렉터리가 바뀌면 거절한다", async () => {
  const path = join(photoDir, "101.jpg");
  await writeFile(path, JPEG);
  const handle = await open(path, "r");
  try {
    const opened = await handle.stat();
    expect(await stillInPlace(photoDir, attachmentDir, path, opened)).toBe(true);

    // 연 뒤 같은 이름에 다른 파일이 들어오면 연 파일과 inode 가 다르다.
    await rename(path, join(photoDir, "moved.jpg"));
    await writeFile(path, JPEG);
    expect(await stillInPlace(photoDir, attachmentDir, path, opened)).toBe(false);

    // 연 뒤 사진 디렉터리가 다른 사용자의 디렉터리를 가리키는 링크로 바뀌어도 거절한다.
    await writeFile(join(otherDir, "101.jpg"), JPEG);
    await rename(photoDir, join(attachmentDir, "photos-moved"));
    await symlink(otherDir, photoDir);
    expect(await stillInPlace(photoDir, attachmentDir, path, opened)).toBe(false);
  } finally {
    await handle.close();
  }
});
