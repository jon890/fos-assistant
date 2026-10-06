package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ExecutionQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionQuestionRepository extends JpaRepository<ExecutionQuestion, Long> {}
