import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { DevDocMarkdown } from "@/components/dev-docs/markdown";
import { DevDocsShell } from "@/components/dev-docs/shell";
import { Notice } from "@/components/ui/notice";
import { getCatalog } from "@/lib/dev-docs/catalog";
import { readRoadmap } from "@/lib/dev-docs/read";
import { sourceCommit } from "@/lib/dev-docs/source";

export const metadata: Metadata = { title: "개발 문서" };

/** 「개발 문서」 의 첫 화면이다. 로그인한 사용자 누구나 연다. 처음에 로드맵을 보인다(spec.md 의 DECISION14). */
export default async function DevDocsPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");

  const catalog = await getCatalog();
  if (!catalog) {
    return (
      <Notice variant="warning">개발 문서를 담지 않고 띄운 서버예요.</Notice>
    );
  }
  const commit = sourceCommit();
  const roadmap = await readRoadmap(catalog);
  return (
    <DevDocsShell catalog={catalog} commit={commit}>
      {roadmap ? (
        <DevDocMarkdown
          markdown={roadmap}
          fromPath="docs/prd.md"
          known={[...catalog.paths]}
          commit={commit}
        />
      ) : (
        <Notice variant="neutral">로드맵이 아직 없어요.</Notice>
      )}
    </DevDocsShell>
  );
}
