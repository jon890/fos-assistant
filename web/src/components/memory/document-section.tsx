"use client";

import { useEffect, useState } from "react";
import {
  listDocuments,
  type MemoryCollectionOption,
  type MemoryDocument,
} from "@/lib/memory-document";
import { MEMORY_IMPORTED_EVENT } from "@/lib/memory-import";
import { DocumentForm } from "./document-form";
import { DocumentItem } from "./document-item";

export type { MemoryCollectionOption, MemoryDocument };

export function DocumentSection({
  initialDocuments,
  collections,
}: {
  initialDocuments: MemoryDocument[];
  collections: MemoryCollectionOption[];
}) {
  const [documents, setDocuments] = useState(initialDocuments);
  const names = new Map(
    collections.map((option) => [option.key, option.displayName]),
  );

  async function reload() {
    const result = await listDocuments();
    if (result.ok) setDocuments(result.data);
  }

  // 가져오기 절이 끝났다고 알리면 새로 들어온 문서를 다시 읽는다.
  useEffect(() => {
    window.addEventListener(MEMORY_IMPORTED_EVENT, reload);
    return () => window.removeEventListener(MEMORY_IMPORTED_EVENT, reload);
  });

  return (
    <section className="mb-8" aria-labelledby="memory-documents-heading">
      <h2 id="memory-documents-heading" className="mb-1 text-lg font-semibold">
        문서
      </h2>
      <p className="mb-3 max-w-2xl text-sm leading-6 text-muted-foreground">
        길게 적어 두고 이름으로 찾는 글이에요. 직접 쓴 문서만 저장돼요.
      </p>
      <DocumentForm collections={collections} onCreated={reload} />
      <div className="grid gap-3">
        {documents.length > 0 ? (
          documents.map((document) => (
            <DocumentItem
              key={document.id}
              document={document}
              collectionName={
                names.get(document.collection) ?? document.collection
              }
              onChanged={reload}
            />
          ))
        ) : (
          <p className="text-sm text-muted-foreground">아직 문서가 없어요.</p>
        )}
      </div>
    </section>
  );
}
