"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { FollowUpDialog } from "./follow-up-dialog";

/**
 * 「할 일 더하기」 단추와 그 대화 상자다. 「내 차례」 카드 끝과, 다섯 카드가 모두 빈 화면 아래에 같은 것을 둔다.
 * 저장하면 서버 부품을 다시 읽는다.
 */
export function AddFollowUpButton() {
  const router = useRouter();
  const [adding, setAdding] = useState(false);
  return (
    <>
      <Button
        variant="outline"
        size="sm"
        className="self-start"
        onClick={() => setAdding(true)}
      >
        할 일 더하기
      </Button>
      <FollowUpDialog
        open={adding}
        onOpenChange={setAdding}
        followUp={null}
        onSaved={() => {
          setAdding(false);
          router.refresh();
        }}
      />
    </>
  );
}
