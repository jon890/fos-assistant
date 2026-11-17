import type { ReactNode } from "react";
import type { Catalog } from "@/lib/dev-docs/catalog";
import { githubCommitUrl } from "@/lib/dev-docs/source";
import { DevDocsCatalog } from "./catalog";

/** 「개발 문서」 화면의 틀이다. 머리의 커밋, 옆의 목록, 본문 자리를 둔다. 고치는 단추는 두지 않는다. */
export function DevDocsShell({
  catalog,
  commit,
  currentPath,
  children,
}: {
  catalog: Catalog;
  commit: string | null;
  currentPath?: string;
  children: ReactNode;
}) {
  return (
    <div className="mx-auto w-full max-w-6xl">
      <div className="mb-4 flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h1 className="text-xl font-semibold">개발 문서</h1>
        <p className="text-sm text-muted-foreground">
          {commit ? (
            <>
              커밋{" "}
              <a
                href={githubCommitUrl(commit)}
                target="_blank"
                rel="noopener noreferrer"
                className="font-mono underline underline-offset-2"
              >
                {commit.slice(0, 7)}
              </a>
              {" 의 문서예요. 고치기는 PR 로 해요."}
            </>
          ) : (
            "커밋 정보 없음"
          )}
        </p>
      </div>
      <div className="flex flex-col gap-6 lg:flex-row">
        <aside className="lg:w-72 lg:shrink-0">
          <DevDocsCatalog groups={catalog.groups} currentPath={currentPath} />
        </aside>
        <main className="min-w-0 flex-1">{children}</main>
      </div>
    </div>
  );
}
