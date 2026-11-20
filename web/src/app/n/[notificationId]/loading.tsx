import { PageSkeleton } from "@/components/ui/page-skeleton";

/** 읽음 표시를 기다리는 동안의 뼈대다. 푸시 대부분이 대화로 넘어가므로 대화 뼈대를 쓴다. */
export default function Loading() {
  return <PageSkeleton shape="chat" width="3xl" />;
}
