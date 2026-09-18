package com.bifos.assistant.usage.application;

/**
 * 실행 하나가 속한 나무 전체다.
 *
 * @param truncated 이 나무 어딘가를 잘랐다. 화면이 한 번에 알아채는 값이고, 어느 자리를 잘랐는지는
 *     {@link ExecutionNode#truncated()} 가 알린다
 */
public record ExecutionTree(
        ExecutionNode root,
        boolean truncated) {
}
