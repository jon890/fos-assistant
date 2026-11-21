package com.bifos.assistant.mcp.application.dto;

import com.bifos.assistant.chat.attachment.application.dto.InspectedImage;

/**
 * 받기 경로가 돌려주는 사진 하나다.
 *
 * @param image 원본 바이트와 형식
 * @param link 링크 가리킴이면 Control Plane 이 정해진 꼴로 바꾼 받은 주소. 그 밖에는 null 이다(ADR-20261120 / link-image-source)
 * @param found 사용자가 보낸 링크가 아니라 비서가 그 대화에서 찾은 주소로 받았다(ADR-20261121 / found-link-image-source)
 */
public record ImageSourceResult(InspectedImage image, String link, boolean found) {
}
