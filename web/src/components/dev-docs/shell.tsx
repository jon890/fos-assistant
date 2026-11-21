import type { ReactNode } from "react";
import type { Catalog } from "@/lib/dev-docs/catalog";
import { PageFrame } from "@/components/ui/page-frame";
import { githubCommitUrl } from "@/lib/dev-docs/source";
import { PAGE_HEADS } from "@/lib/page-head";
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
    <PageFrame
      title={PAGE_HEADS.devDocs.title}
      description={
        commit ? (
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
        )
      }
    >
      <div className="flex flex-col gap-6 lg:flex-row">
        <aside className="lg:w-72 lg:shrink-0">
          <DevDocsCatalog groups={catalog.groups} currentPath={currentPath} />
        </aside>
        {/* 화면 틀(app-shell)이 이미 main 이라 본문은 이름 붙인 section 으로 둔다. */}
        <section aria-label="문서 본문" className="min-w-0 flex-1">
          {children}
        </section>
      </div>
    </PageFrame>
  );
}
