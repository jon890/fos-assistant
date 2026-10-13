import { redirect } from "next/navigation";

/** 가계부 전용 외부 서비스 연결 화면이 있던 옛 주소다. 범용 외부 서비스 연결 화면으로 넘긴다. */
export default function LegacyAccountbookConnectionPage() {
  redirect("/connections/fos-accountbook");
}
