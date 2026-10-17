import { createHash, randomUUID } from "node:crypto";
import { readFile, rename, unlink, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { withWriteTab, type ReadOptions } from "./drafts.ts";
import { readCategories } from "./editor/settings.ts";
import { ToolError } from "./errors.ts";
import { checkJobDir, DEFAULT_JOB_DIR, openJobDir } from "./jobs.ts";
import { BLOG_ID_PATTERN, type Env } from "./session.ts";

/** 읽은 목록을 믿는 시간. 이보다 오래된 목록으로는 막지 않는다. 그 사이 사용자가 카테고리를 만들 수 있어서다. */
export const CATEGORY_CACHE_MS = 30 * 60_000;
/** 안내 문장에 싣는 카테고리 이름의 최대 개수. */
const PROBLEM_NAMES_MAX = 50;

/** 블로그마다 하나인 목록 파일. 아이디의 대소문자를 가리지 않으므로 소문자로 맞춘 뒤 해시한다. */
function cacheFile(dir: string, blogId: string) {
  const hash = createHash("sha256").update(blogId.toLowerCase()).digest("hex");
  return join(dir, `categories-${hash.slice(0, 16)}.json`);
}

/**
 * 블로그의 실제 카테고리 이름을 차례대로 돌려준다. 항목은 누르지 않고 글도 저장하지 않는다.
 * 읽은 목록은 작업 디렉터리에 남겨, 목록에 없는 이름의 초안을 승인 카드 전에 막는 데 쓴다.
 * 「작성 중인 글」 알림이 떠 있으면 설정을 열 수 없어 `NAVER_BLOG_EDITOR_IN_USE` 다.
 */
export function listCategories(env: Env, options: ReadOptions = {}) {
  return withWriteTab(env, options, async (page, blogId, notice) => {
    if (notice) throw new ToolError("NAVER_BLOG_EDITOR_IN_USE");
    const categories = await readCategories(page);
    let temporary: string | null = null;
    try {
      const file = cacheFile(await openJobDir(env), blogId);
      temporary = `${file}.${randomUUID()}.tmp`;
      await writeFile(
        temporary,
        JSON.stringify({ read_at: Date.now(), categories }),
        { mode: 0o600 },
      );
      await rename(temporary, file);
    } catch {
      // 목록을 남기지 못해도 읽은 결과는 돌려준다. 막는 검사만 건너뛴다.
      if (temporary) await unlink(temporary).catch(() => {});
    }
    return { count: categories.length, categories };
  });
}

/** 최근에 읽은 목록. 없거나 오래됐거나 모양이 다르면 `null` 이다. */
async function recentCategories(env: Env, now: number) {
  const blogId = env.NAVER_BLOG_ID ?? "";
  if (!BLOG_ID_PATTERN.test(blogId)) return null;
  try {
    const dir = env.NAVER_BLOG_JOB_DIR || DEFAULT_JOB_DIR;
    await checkJobDir(dir);
    const saved = JSON.parse(await readFile(cacheFile(dir, blogId), "utf8"));
    if (
      typeof saved?.read_at !== "number" ||
      now - saved.read_at < 0 ||
      now - saved.read_at > CATEGORY_CACHE_MS ||
      !Array.isArray(saved.categories) ||
      saved.categories.length === 0 ||
      saved.categories.some((name: unknown) => typeof name !== "string")
    )
      return null;
    return saved.categories as string[];
  } catch {
    return null;
  }
}

/**
 * 최근 `list_categories` 로 읽은 목록에 그 이름이 없으면 고칠 곳을 알리는 문장을 돌려준다.
 * 읽은 목록이 없거나 오래됐으면 막지 않는다. 그때는 저장 작업의 `category_not_found` 가 마지막으로 막는다.
 */
export async function categoryProblem(
  env: Env,
  category: string,
  now = Date.now(),
) {
  const names = await recentCategories(env, now);
  if (!names || names.includes(category.trim())) return null;
  const shown = names.slice(0, PROBLEM_NAMES_MAX).join(", ");
  return (
    `카테고리 "${category}" 는 블로그에 없습니다. 있는 카테고리: ${shown}. ` +
    "이 가운데에서 고르거나 list_categories 로 목록을 다시 읽으세요."
  );
}
