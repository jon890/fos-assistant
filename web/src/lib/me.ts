import { callControlPlane } from "@/lib/control-plane";

export type Me = {
  id: number;
  email: string;
  displayName: string;
  role: "ADMIN" | "MEMBER";
};

export async function readMe(): Promise<Me | null> {
  try {
    const result = await callControlPlane<Me>("/api/v1/me");
    return result.ok ? result.data : null;
  } catch {
    return null;
  }
}
