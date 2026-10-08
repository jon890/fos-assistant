export type CatalogToolset = {
  name: string;
  label: string;
  description: string;
  hidden: boolean;
  enabledAgents: { code: string; name: string }[];
};

export async function fetchToolsetCatalog(): Promise<CatalogToolset[]> {
  const response = await fetch("/api/admin/toolsets");
  if (!response.ok) throw new Error("도구 목록 조회 실패");
  return (await response.json()) as CatalogToolset[];
}

export async function saveHiddenToolsets(hidden: string[]): Promise<void> {
  const response = await fetch("/api/admin/toolsets", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ hidden }),
  });
  if (!response.ok) throw new Error("도구 숨김 저장 실패");
}
