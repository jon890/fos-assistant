"use client";

import { LoaderCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import type { OutgoingMessage } from "./composer-attachment-utils";

export function OutgoingMessageView({ message }: { message: OutgoingMessage }) {
  return (
    <li className="flex min-w-0 justify-end animate-message-user">
      <div
        data-testid="user-message"
        className="max-w-[70%] rounded-xl rounded-br-sm bg-primary-soft px-4 py-2.5"
      >
        <p className="whitespace-pre-wrap break-words text-sm leading-6">
          {message.text}
        </p>
        <div className="mt-2 flex flex-wrap gap-2">
          {message.items.map((item) => (
            <div
              key={item.key}
              data-testid="outgoing-attachment"
              className="flex w-24 flex-col gap-1"
            >
              <div className="relative h-24 w-24 overflow-hidden rounded-md border border-border bg-muted">
                {item.previewUrl ? (
                  <img
                    data-testid="outgoing-thumbnail"
                    src={item.previewUrl}
                    alt={item.file.name}
                    className="h-full w-full object-cover"
                  />
                ) : null}
                {item.status === "uploading" ? (
                  <span
                    data-testid="outgoing-uploading"
                    role="status"
                    className="absolute inset-0 flex flex-col items-center justify-center gap-1 bg-background/80 text-xs text-foreground"
                  >
                    <LoaderCircle
                      aria-hidden="true"
                      className="size-4 animate-spin motion-reduce:animate-none"
                    />
                    올리는 중이에요
                  </span>
                ) : null}
              </div>
              {item.status === "error" ? (
                <>
                  <Notice variant="error" className="text-xs">
                    {item.errorMessage}
                  </Notice>
                  <Button
                    size="xs"
                    variant="outline"
                    onClick={() => message.retry(item.key)}
                  >
                    다시 시도
                  </Button>
                  <Button
                    size="xs"
                    variant="outline"
                    onClick={() => message.omit(item.key)}
                  >
                    빼고 보내기
                  </Button>
                </>
              ) : null}
            </div>
          ))}
        </div>
        {message.errorMessage ? (
          <Notice variant="error" className="mt-2">
            {message.errorMessage}
            <Button size="xs" variant="link" onClick={message.retrySend}>
              다시 보내기
            </Button>
          </Notice>
        ) : null}
      </div>
    </li>
  );
}
