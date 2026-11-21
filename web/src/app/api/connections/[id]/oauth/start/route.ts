import {
  connectionResponse,
  invalidConnectionRequest,
  isConnectorId,
  readValuesBody,
} from "@/lib/connection-route";
import { safeStart } from "@/lib/connection-oauth";
import { oauthCall, oauthCallbackUrl } from "@/lib/connection-oauth-route";
import { rejectCrossOrigin } from "@/lib/same-origin";

/** 단추 연결을 시작한다. 본문은 단추 밖 칸 값(`values`)뿐이고 돌아오는 주소는 서버가 만든다. */
export async function POST(
  request: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const rejected = rejectCrossOrigin(request);
  if (rejected) return rejected;
  const { id } = await params;
  if (!isConnectorId(id)) return invalidConnectionRequest();
  const values = await readValuesBody(request);
  if (!values) return invalidConnectionRequest();
  return connectionResponse(
    await oauthCall(
      `/api/v1/connections/${id}/oauth/start`,
      { redirectUri: oauthCallbackUrl(request), values },
      safeStart,
    ),
  );
}
