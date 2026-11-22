package com.bifos.assistant.chat.artifact.application;

import com.bifos.assistant.chat.artifact.domain.LinkImageRejectedException;
import com.bifos.assistant.chat.artifact.infra.LinkImage;
import com.bifos.assistant.chat.artifact.infra.LinkImageFetcher;
import com.bifos.assistant.chat.attachment.application.dto.InspectedImage;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 사용자가 대화에 보낸 링크의 사진을 받는다(ADR-20261120 / link-image-source). 받은 바이트는 저장하지 않는다.
 *
 * <p>링크가 그 대화에 있는지는 부르는 쪽이 먼저 확인한다. 여기서는 받기와 까닭별 오류 코드만 맡는다.
 */
@Service
@RequiredArgsConstructor
public class LinkImageService {
    private final LinkImageFetcher fetcher;


    /**
     * @param link 대화에서 확인하고 정해진 꼴로 바꾼 https 주소
     * @throws ApiException {@code IMAGE_SOURCE_LINK_*} 가운데 하나
     */
    public InspectedImage read(URI link) {
        return read(link, false);
    }


    /** 페이지에서 이미 선택한 사진 주소를 기존 안전 검사로 받는다. */
    public InspectedImage readDirect(URI link) {
        return read(link, true);
    }


    private InspectedImage read(URI link, boolean direct) {
        try {
            LinkImage image = direct ? fetcher.fetchDirect(link) : fetcher.fetch(link);
            return new InspectedImage(image.contentType(), image.bytes());
        } catch (LinkImageRejectedException ex) {
            throw new ApiException(code(ex.reason()), "link image is not usable", ex);
        }
    }


    private static ErrorCode code(LinkImageRejectedException.Reason reason) {
        return switch (reason) {
            case BLOCKED -> ErrorCode.IMAGE_SOURCE_LINK_BLOCKED;
            case NO_IMAGE -> ErrorCode.IMAGE_SOURCE_LINK_NO_IMAGE;
            case TOO_LARGE -> ErrorCode.IMAGE_SOURCE_LINK_TOO_LARGE;
            case FAILED -> ErrorCode.IMAGE_SOURCE_LINK_FAILED;
        };
    }
}
