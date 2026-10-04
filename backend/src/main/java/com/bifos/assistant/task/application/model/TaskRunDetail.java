package com.bifos.assistant.task.application.model;

import com.bifos.assistant.task.domain.TaskRun;
import java.util.UUID;

/**
 * 발화 한 번과 그 결과를 남긴 대화의 공개 식별자다.
 *
 * @param conversationId 대화가 없으면 null
 */
public record TaskRunDetail(TaskRun run, UUID conversationId) {}
