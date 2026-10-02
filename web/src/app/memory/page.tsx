import { redirect } from "next/navigation";
import { auth } from "@/auth";
import {
  DocumentSection,
  type MemoryCollectionOption,
  type MemoryDocument,
} from "@/components/memory/document-section";
import { MemoryList, type Memory } from "@/components/memory/memory-list";
import { callControlPlane } from "@/lib/control-plane";
import { readMe } from "@/lib/me";

export default async function MemoryPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  const [result, documents, collections, me] = await Promise.all([
    callControlPlane<Memory[]>("/api/v1/memories"),
    callControlPlane<MemoryDocument[]>("/api/v1/memory-documents"),
    callControlPlane<MemoryCollectionOption[]>("/api/v1/memory-collections"),
    readMe(),
  ]);
  if (!result.ok) return <p className="text-sm">{result.message}</p>;
  // 문서 절을 읽지 못해도 기존 기억 화면은 그대로 그린다.
  const sections =
    documents.ok && collections.ok ? (
      <DocumentSection
        initialDocuments={documents.data}
        collections={collections.data}
      />
    ) : null;
  return (
    <MemoryList
      initialMemories={result.data}
      isAdmin={me?.role === "ADMIN"}
      currentUserId={me?.id}
      after={sections}
    />
  );
}
