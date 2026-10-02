import { redirect } from "next/navigation";
import { auth } from "@/auth";
import {
  DocumentSection,
  type MemoryCollectionOption,
  type MemoryDocument,
} from "@/components/memory/document-section";
import { ImportSection } from "@/components/memory/import-section";
import { MemoryList, type Memory } from "@/components/memory/memory-list";
import { ServiceTokenPanel } from "@/components/memory/service-token-panel";
import { callControlPlane } from "@/lib/control-plane";
import { readMe } from "@/lib/me";
import type { ServiceToken } from "@/lib/service-token-api";

export default async function MemoryPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  const [result, documents, collections, tokens, me] = await Promise.all([
    callControlPlane<Memory[]>("/api/v1/memories"),
    callControlPlane<MemoryDocument[]>("/api/v1/memory-documents"),
    callControlPlane<MemoryCollectionOption[]>("/api/v1/memory-collections"),
    callControlPlane<ServiceToken[]>("/api/v1/service-tokens"),
    readMe(),
  ]);
  if (!result.ok) return <p className="text-sm">{result.message}</p>;
  // 문서 절과 외부 서비스 연결 절은 각각 읽지 못하면 그 절만 빼고 기존 기억 화면은 그대로 그린다.
  const sections = (
    <>
      {documents.ok && collections.ok ? (
        <DocumentSection
          initialDocuments={documents.data}
          collections={collections.data}
        />
      ) : null}
      {tokens.ok && collections.ok ? (
        <ServiceTokenPanel
          initialTokens={tokens.data}
          collections={collections.data}
        />
      ) : null}
      {documents.ok && collections.ok ? (
        <ImportSection collections={collections.data} />
      ) : null}
    </>
  );
  return (
    <MemoryList
      initialMemories={result.data}
      isAdmin={me?.role === "ADMIN"}
      currentUserId={me?.id}
      after={sections}
    />
  );
}
