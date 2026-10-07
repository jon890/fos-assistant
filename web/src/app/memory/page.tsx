import { redirect } from "next/navigation";
import { auth } from "@/auth";
import {
  DocumentSection,
  type MemoryCollectionOption,
  type MemoryDocument,
} from "@/components/memory/document-section";
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
  const readAt = new Date().toISOString();
  // 문서 탭과 접어 둔 절은 각각 읽지 못하면 그 부분만 빼고 기억 목록은 그대로 그린다.
  const documentsReady = documents.ok && collections.ok;
  const tokensReady = tokens.ok && collections.ok;
  return (
    <MemoryList
      initialMemories={result.data}
      isAdmin={me?.role === "ADMIN"}
      currentUserId={me?.id}
      readAt={readAt}
      documents={
        documentsReady ? (
          <DocumentSection
            initialDocuments={documents.data}
            collections={collections.data}
            readAt={readAt}
          />
        ) : null
      }
      advanced={
        tokensReady ? (
          <ServiceTokenPanel
            initialTokens={tokens.data}
            collections={collections.data}
          />
        ) : null
      }
    />
  );
}
