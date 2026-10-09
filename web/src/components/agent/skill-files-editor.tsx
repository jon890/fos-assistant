"use client";

import { Button } from "@/components/ui/button";
import { NativeSelect } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";

export type SkillFileEntry = {
  key: number;
  directory: "references" | "templates";
  fileName: string;
  size: number;
  content?: string;
};

/** 기존 참고 파일도 본문을 직접 열어 고친다. */
export function SkillFilesEditor({ files, onChange, onRemove }: {
  files: SkillFileEntry[];
  onChange(key: number, changes: Partial<SkillFileEntry>): void;
  onRemove(key: number): void;
}) {
  return files.length === 0 ? (
    <p className="mt-2 text-sm text-muted-foreground">아직 참고 파일이 없어요.</p>
  ) : (
    <ul className="mt-2 divide-y divide-border rounded-md border border-border">
      {files.map((entry) => {
        const filePath = `${entry.directory}/${entry.fileName}`;
        return (
          <li key={entry.key} className="p-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="text-sm break-all">{filePath}</span>
              <div className="flex items-center gap-2">
                <NativeSelect value={entry.directory} className="w-auto"
                  aria-label={`${entry.fileName} 위치`}
                  onChange={(event) => onChange(entry.key, { directory: event.target.value as SkillFileEntry["directory"] })}>
                  <option value="references">references/</option>
                  <option value="templates">templates/</option>
                </NativeSelect>
                <Button size="sm" variant="ghost" aria-label={`${filePath} 빼기`}
                  onClick={() => onRemove(entry.key)}>빼기</Button>
              </div>
            </div>
            <details className="mt-2">
              <summary className="cursor-pointer text-sm underline underline-offset-4">{filePath} 내용 고치기</summary>
              <Textarea aria-label={`${filePath} 본문`} value={entry.content ?? ""} rows={8}
                className="mt-2 field-sizing-fixed font-mono"
                onChange={(event) => onChange(entry.key, {
                  content: event.target.value,
                  size: new TextEncoder().encode(event.target.value).length,
                })} />
            </details>
          </li>
        );
      })}
    </ul>
  );
}
