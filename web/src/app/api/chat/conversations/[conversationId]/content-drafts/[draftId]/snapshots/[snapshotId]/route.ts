import { contentDraftGet } from "@/lib/content-draft-route";

type Context = {
  params: Promise<{
    conversationId: string;
    draftId: string;
    snapshotId: string;
  }>;
};

/** 승인 전에 보일 canonical 원고를 읽는다. 원고가 바뀌었으면 Control Plane 이 본문 없이 409 를 준다. */
export async function GET(_: Request, { params }: Context) {
  const { conversationId, draftId, snapshotId } = await params;
  return contentDraftGet(
    `/api/v1/chat/conversations/${conversationId}/content-drafts/${draftId}/snapshots/${snapshotId}`,
    [conversationId, draftId, snapshotId],
  );
}
