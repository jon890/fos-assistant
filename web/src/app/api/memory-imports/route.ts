import { forwardImport } from "./forward";

export async function POST(request: Request) {
  return forwardImport(request, "/api/v1/memory-imports");
}
