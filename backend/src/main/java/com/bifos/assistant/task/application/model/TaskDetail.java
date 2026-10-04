package com.bifos.assistant.task.application.model;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.TaskTrigger;

/**
 * 작업 하나와 그 시각과 에이전트다.
 *
 * @param agent 에이전트 행이 없으면 null
 */
public record TaskDetail(Task task, TaskTrigger trigger, Agent agent) {}
