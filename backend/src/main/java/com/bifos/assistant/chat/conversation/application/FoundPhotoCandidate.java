package com.bifos.assistant.chat.conversation.application;

import com.bifos.assistant.chat.conversation.application.dto.FoundImage.Kind;
import java.net.URI;

/** 읽은 페이지의 사진 후보를 대화에서 확인할 때 쓰는 절대 주소와 해시다. */
record FoundPhotoCandidate(URI uri, String hash, Kind kind, long size) {
}
