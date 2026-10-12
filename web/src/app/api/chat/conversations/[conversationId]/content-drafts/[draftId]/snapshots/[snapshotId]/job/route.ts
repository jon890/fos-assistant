import { contentDraftGet } from "@/lib/content-draft-route";

type Context = {
  params: Promise<{
    conversationId: string;
    draftId: string;
    snapshotId: string;
  }>;
};

/** 그 snapshot 의 저장 job 상태를 읽는다. 저장을 다시 시작하지 않는다. */
export async function GET(_: Request, { params }: Context) {
  const { conversationId, draftId, snapshotId } = await params;
  return contentDraftGet(
    `/api/v1/chat/conversations/${conversationId}/content-drafts/${draftId}/snapshots/${snapshotId}/job`,
    [conversationId, draftId, snapshotId],
  );
}
