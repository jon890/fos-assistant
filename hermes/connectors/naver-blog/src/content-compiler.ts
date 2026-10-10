import { createHash } from "node:crypto";
import {
  CONTENT_COMPILER_VERSION, canonicalDraftV1Schema, compiledSnapshotV1Schema,
  resolvedAssetSchema, type CanonicalDraftV1, type CanonicalBlockTuple,
  type CompiledSnapshotV1, type ResolvedAsset,
} from "./content-draft.ts";
import type { DraftInput } from "./draft.ts";

export type ContentDraftErrorCode = "CONTENT_DRAFT_INVALID" | "CONTENT_DRAFT_RESERVED_LINE"
  | "CONTENT_DRAFT_ASSET_INVALID";

/** 입력 본문과 경로를 오류에 싣지 않는다. */
export class ContentDraftError extends Error {
  constructor(readonly code: ContentDraftErrorCode) { super(code); }
}

const hash = (value: unknown) => createHash("sha256").update(JSON.stringify(value), "utf8").digest("hex");

/** 입력과 분리된 JSON 값 전체를 고정한다. */
function freeze<T>(value: T): T {
  if (value !== null && typeof value === "object") {
    for (const child of Object.values(value)) freeze(child);
    Object.freeze(value);
  }
  return value;
}

/** 서버가 검증한 원본 참조와 원고를 기존 DSL과 재계산 가능한 snapshot으로 바꾼다. */
export function compileContentDraft(draft: CanonicalDraftV1, assets: ResolvedAsset[]): CompiledSnapshotV1 {
  const parsedDraft = canonicalDraftV1Schema.safeParse(draft);
  if (!parsedDraft.success) {
    const reserved = parsedDraft.error.issues.some((issue) => issue.message === "CONTENT_DRAFT_RESERVED_LINE");
    throw new ContentDraftError(reserved ? "CONTENT_DRAFT_RESERVED_LINE" : "CONTENT_DRAFT_INVALID");
  }
  const parsedAssets = resolvedAssetSchema.array().safeParse(assets);
  if (!parsedAssets.success) throw new ContentDraftError("CONTENT_DRAFT_ASSET_INVALID");
  const byId = new Map<string, ResolvedAsset>();
  const directories = new Set<string>();
  for (const asset of parsedAssets.data) {
    if (byId.has(asset.assetId)) throw new ContentDraftError("CONTENT_DRAFT_ASSET_INVALID");
    byId.set(asset.assetId, asset);
    directories.add(asset.photoDir);
  }
  if (directories.size > 1) throw new ContentDraftError("CONTENT_DRAFT_ASSET_INVALID");

  const canonical = parsedDraft.data;
  const lines: string[] = [];
  const canonicalBlocks: CanonicalBlockTuple[] = [];
  const assetManifest: CompiledSnapshotV1["assetManifest"] = [];
  const photoNotes: Record<string, string> = {};
  const noteTuples: [number, string][] = [];
  canonical.blocks.forEach((block, position) => {
    switch (block.type) {
      case "paragraph":
        lines.push(block.text);
        canonicalBlocks.push(["paragraph", block.text]);
        break;
      case "image": {
        const asset = byId.get(block.assetId);
        if (!asset) throw new ContentDraftError("CONTENT_DRAFT_ASSET_INVALID");
        const number = assetManifest.length + 1;
        lines.push(`[사진 ${number}: ${asset.fileName}]`);
        if (block.caption !== undefined) lines.push(block.caption);
        canonicalBlocks.push(["image", block.assetId, block.caption ?? null,
          block.observationRef ? [block.observationRef.id, block.observationRef.revision] : null]);
        assetManifest.push({ position, assetId: asset.assetId,
          sourceFingerprint: asset.sourceFingerprint, fileName: asset.fileName });
        if (asset.observationNote !== undefined) {
          photoNotes[String(number)] = asset.observationNote;
          noteTuples.push([number, asset.observationNote]);
        }
        break;
      }
      case "sticker":
        lines.push(`[스티커: ${block.code}]`);
        canonicalBlocks.push(["sticker", block.code]);
        break;
      case "map":
        lines.push(`[지도: ${block.name} | ${block.address}]`);
        canonicalBlocks.push(["map", block.name, block.address]);
        break;
    }
  });
  const payload: DraftInput = { title: canonical.title, category: canonical.category,
    tags: [...canonical.tags], body: lines.join("\n"),
    ...(assetManifest.length ? { photo_dir: parsedAssets.data[0]!.photoDir } : {}) };
  if ([...payload.body].length > 20_000) throw new ContentDraftError("CONTENT_DRAFT_INVALID");
  const payloadFingerprint = hash([payload.title, payload.category, payload.tags, payload.body,
    payload.photo_dir ?? null]);
  const assetTuples = assetManifest.map((asset) =>
    [asset.position, asset.assetId, asset.sourceFingerprint, asset.fileName]);
  const snapshotHash = hash([1, CONTENT_COMPILER_VERSION, canonical.draftId, canonical.revision,
    canonicalBlocks, assetTuples, payloadFingerprint, noteTuples]);
  return freeze(compiledSnapshotV1Schema.parse({ schemaVersion: 1, compilerVersion: CONTENT_COMPILER_VERSION,
    draftId: canonical.draftId, revision: canonical.revision, canonicalBlocks, payload,
    photoNotes, assetManifest, payloadFingerprint, snapshotHash }));
}
