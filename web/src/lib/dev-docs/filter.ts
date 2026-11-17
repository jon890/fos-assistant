import type { DocEntry, DocGroup } from "./catalog.ts";

/** 찾기 결과의 줄이다. 기능의 스펙이나 설계만 맞았으면 `expanded` 가 참이다. */
export type FilteredEntry = DocEntry & { expanded?: boolean };

export type FilteredSection = {
  label: string;
  entries: FilteredEntry[];
  archived?: FilteredEntry[];
};

export type FilteredGroup = Omit<DocGroup, "sections"> & {
  sections: FilteredSection[];
};

function matches(text: string, query: string): boolean {
  return text.toLowerCase().includes(query);
}

function filterEntry(entry: DocEntry, query: string): FilteredEntry | null {
  const self = matches(entry.title, query) || matches(entry.fileStem, query);
  // 스펙과 설계는 파일 이름이 모두 같아 목록 이름만 맞춘다(spec.md 의 DECISION12).
  const children = entry.children?.filter((child) =>
    matches(child.title, query),
  );
  if (self) return entry;
  if (children && children.length > 0)
    return { ...entry, children: entry.children, expanded: true };
  return null;
}

function filterList(entries: DocEntry[], query: string): FilteredEntry[] {
  return entries
    .map((entry) => filterEntry(entry, query))
    .filter((entry): entry is FilteredEntry => entry !== null);
}

/**
 * 목록 이름과 확장자를 뺀 파일 이름에 글자가 든 줄만 남긴다. 대소문자를 가리지 않는다.
 * 빈 글자면 그대로 돌려준다. 남은 줄이 없는 나눔과 묶음은 뺀다.
 */
export function filterCatalog(
  groups: DocGroup[],
  rawQuery: string,
): FilteredGroup[] {
  const query = rawQuery.trim().toLowerCase();
  if (query === "") return groups;
  return groups
    .map((group) => ({
      ...group,
      sections: group.sections
        .map((section) => {
          const entries = filterList(section.entries, query);
          const archived = section.archived
            ? filterList(section.archived, query)
            : [];
          return {
            label: section.label,
            entries,
            ...(archived.length > 0 ? { archived } : {}),
          };
        })
        .filter((section) => section.entries.length > 0 || section.archived),
    }))
    .filter((group) => group.sections.length > 0);
}
