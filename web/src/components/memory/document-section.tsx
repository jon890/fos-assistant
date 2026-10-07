"use client";

import { useEffect, useState } from "react";
import {
  listDocuments,
  type MemoryCollectionOption,
  type MemoryDocument,
} from "@/lib/memory-document";
import { MEMORY_MANUAL_CREATE } from "@/lib/memory-features";
import { MEMORY_IMPORTED_EVENT } from "@/lib/memory-import";
import { DocumentForm } from "./document-form";
import { DocumentItem } from "./document-item";

export type { MemoryCollectionOption, MemoryDocument };

export function DocumentSection({
  initialDocuments,
  collections,
  readAt,
}: {
  initialDocuments: MemoryDocument[];
  collections: MemoryCollectionOption[];
  /** 서버가 목록을 읽은 시각이다. 상대 시각을 이 시각 기준으로 센다 */
  readAt: string;
}) {
  const [documents, setDocuments] = useState(initialDocuments);
  /** 펼친 문서다. 한 번에 하나만 펼친다. */
  const [openId, setOpenId] = useState<number | null>(null);
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
        길게 적어 두고 이름으로 찾는 글이에요. 눌러서 열면 내용을 보고 고치거나
        지울 수 있어요.
      </p>
      {MEMORY_MANUAL_CREATE ? (
        <DocumentForm collections={collections} onCreated={reload} />
      ) : null}
      {documents.length > 0 ? (
        <ul className="grid gap-2" aria-label="문서 목록">
          {documents.map((document) => (
            <DocumentItem
              key={document.id}
              document={document}
              collectionName={
                names.get(document.collection) ?? document.collection
              }
              open={openId === document.id}
              readAt={readAt}
              onToggle={() =>
                setOpenId((current) =>
                  current === document.id ? null : document.id,
                )
              }
              onChanged={reload}
            />
          ))}
        </ul>
      ) : (
        <p className="text-sm text-muted-foreground">아직 문서가 없어요.</p>
      )}
    </section>
  );
}
