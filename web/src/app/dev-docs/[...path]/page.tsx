import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { auth } from "@/auth";
import { DevDocMarkdown } from "@/components/dev-docs/markdown";
import { DevDocsShell } from "@/components/dev-docs/shell";
import { Notice } from "@/components/ui/notice";
import { getCatalog } from "@/lib/dev-docs/catalog";
import { normalizeRepoPath, readDoc } from "@/lib/dev-docs/read";
import { githubFileUrl, sourceCommit } from "@/lib/dev-docs/source";

export const metadata: Metadata = { title: "개발 문서" };

/** 문서 하나다. 목록에 든 경로만 연다(spec.md 의 REQ9). */
export default async function DevDocPage({
  params,
}: {
  params: Promise<{ path: string[] }>;
}) {
  const { path } = await params;
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");

  const catalog = await getCatalog();
  if (!catalog) {
    return (
      <Notice variant="warning">개발 문서를 담지 않고 띄운 서버예요.</Notice>
    );
  }
  const repoPath = normalizeRepoPath(path);
  if (!repoPath || !catalog.paths.has(repoPath)) notFound();
  const commit = sourceCommit();

  let doc: Awaited<ReturnType<typeof readDoc>>;
  try {
    doc = await readDoc(repoPath, catalog);
  } catch {
    return (
      <DevDocsShell catalog={catalog} commit={commit} currentPath={repoPath}>
        <Notice variant="error" role="alert">
          문서를 불러오지 못했어요.
        </Notice>
      </DevDocsShell>
    );
  }
  if (!doc) notFound();

  return (
    <DevDocsShell catalog={catalog} commit={commit} currentPath={doc.path}>
      <p className="mb-3 flex flex-wrap justify-between gap-2 text-xs text-muted-foreground">
        <span className="font-mono">{doc.path}</span>
        <a
          href={githubFileUrl(doc.path, commit)}
          target="_blank"
          rel="noopener noreferrer"
          className="underline underline-offset-2"
        >
          GitHub 에서 보기
        </a>
      </p>
      <DevDocMarkdown
        markdown={doc.markdown}
        fromPath={doc.path}
        known={[...catalog.paths]}
        commit={commit}
      />
    </DevDocsShell>
  );
}
