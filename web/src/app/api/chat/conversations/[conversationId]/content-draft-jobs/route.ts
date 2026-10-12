import { contentDraftGet, pickJobPage } from "@/lib/content-draft-route";

type Context = { params: Promise<{ conversationId: string }> };

/** 그 대화의 원고 job 을 최근 순으로 한 페이지 읽는다. `beforeSnapshotId` 만 쿼리로 받는다. */
export async function GET(request: Request, { params }: Context) {
  const { conversationId } = await params;
  const before = new URL(request.url).searchParams.get("beforeSnapshotId");
  return contentDraftGet(
    `/api/v1/chat/conversations/${conversationId}/content-draft-jobs${
      before === null ? "" : `?beforeSnapshotId=${before}`
    }`,
    before === null ? [conversationId] : [conversationId, before],
    pickJobPage,
  );
}
