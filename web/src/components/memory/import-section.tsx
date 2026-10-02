"use client";

import { useRouter } from "next/navigation";
import { useId, useState } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import type { MemoryCollectionOption } from "@/lib/memory-document";
import {
  MAX_IMPORT_BYTES,
  MEMORY_IMPORTED_EVENT,
  parseBundle,
  type ImportBundle,
} from "@/lib/memory-import";
import {
  commitImport,
  previewImport,
  type ImportResult,
} from "@/lib/memory-import-api";
import { ImportPreview } from "./import-preview";

const PARSE_FAILURE = {
  NOT_JSON: "가져올 수 있는 파일이 아니에요.",
  WRONG_SHAPE: "가져올 수 있는 파일이 아니에요.",
  EMPTY: "가져올 항목이 없어요.",
  TOO_MANY: "한 번에 100개까지 가져올 수 있어요. 나눠서 올려 주세요.",
} as const;

/**
 * 검토한 묶음 파일을 올려 대조하고 가져오는 절이다(ADR-058).
 * 묶음은 화면의 상태에만 두고 가져오기가 끝나거나 취소하면 지운다. 브라우저 저장소와 주소에 두지 않는다.
 */
export function ImportSection({
  collections,
}: {
  collections: MemoryCollectionOption[];
}) {
  const router = useRouter();
  const inputId = useId();
  const [bundle, setBundle] = useState<ImportBundle | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [busy, setBusy] = useState<"" | "checking" | "saving">("");
  const [error, setError] = useState("");
  const [done, setDone] = useState("");
  const names = new Map(
    collections.map((option) => [option.key, option.displayName]),
  );

  function clear() {
    setBundle(null);
    setResult(null);
  }

  async function choose(event: React.ChangeEvent<HTMLInputElement>) {
    const input = event.currentTarget;
    const file = input.files?.[0];
    // 같은 파일을 다시 골라도 변경으로 읽히도록 고른 값을 비운다.
    input.value = "";
    clear();
    setError("");
    setDone("");
    if (!file) return;
    if (file.size > MAX_IMPORT_BYTES) {
      setError("가져올 파일이 너무 커요. 나눠서 올려 주세요.");
      return;
    }
    const parsed = parseBundle(await file.text());
    if (!parsed.ok) {
      setError(PARSE_FAILURE[parsed.reason]);
      return;
    }
    setBusy("checking");
    const checked = await previewImport(parsed.bundle);
    setBusy("");
    if (!checked.ok) {
      setError(checked.message);
      return;
    }
    setBundle(parsed.bundle);
    setResult(checked.data);
  }

  async function save() {
    if (!bundle) return;
    setBusy("saving");
    setError("");
    const saved = await commitImport(bundle);
    setBusy("");
    if (!saved.ok) {
      setError(saved.message);
      return;
    }
    clear();
    setDone(
      `${saved.data.newCount}개를 가져왔어요. 올린 파일은 이제 지워 주세요.`,
    );
    window.dispatchEvent(new Event(MEMORY_IMPORTED_EVENT));
    router.refresh();
  }

  return (
    <section className="mb-8" aria-labelledby="memory-import-heading">
      <h2 id="memory-import-heading" className="mb-1 text-lg font-semibold">
        기존 기록 가져오기
      </h2>
      <p className="mb-3 max-w-2xl text-sm leading-6 text-muted-foreground">
        다른 곳에 적어 둔 기록을 검토한 파일로 한 번에 가져와요. 가져온 뒤에는
        파일을 지워 주세요.
      </p>
      <label htmlFor={inputId} className="sr-only">
        가져올 파일
      </label>
      <input
        id={inputId}
        type="file"
        accept="application/json,.json"
        disabled={busy !== ""}
        onChange={choose}
        className="mb-3 block w-full max-w-full text-sm"
      />
      {busy === "checking" ? (
        <p className="mb-3 text-sm text-muted-foreground">확인하는 중이에요</p>
      ) : null}
      {error ? (
        <Notice variant="error" role="alert" className="mb-3">
          {error}
        </Notice>
      ) : null}
      {done ? (
        <Notice variant="success" className="mb-3">
          {done}
        </Notice>
      ) : null}
      {bundle && result ? (
        <>
          <ImportPreview bundle={bundle} result={result} names={names} />
          <div className="flex flex-wrap gap-2">
            <Button
              onClick={save}
              disabled={result.newCount === 0 || busy !== ""}
            >
              {result.newCount}개 가져오기
            </Button>
            <Button variant="outline" onClick={clear} disabled={busy !== ""}>
              취소
            </Button>
          </div>
        </>
      ) : null}
    </section>
  );
}
