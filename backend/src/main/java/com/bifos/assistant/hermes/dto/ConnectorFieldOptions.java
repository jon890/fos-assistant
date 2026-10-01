package com.bifos.assistant.hermes.dto;

/**
 * 선택지 칸이 고를 값을 어디서 읽는지다.
 *
 * @param tool 선택지를 내는 읽기 전용 도구
 * @param items 도구 결과에서 선택지 배열이 든 칸
 * @param value 선택지 한 항목에서 저장할 값이 든 칸
 * @param label 선택지 한 항목에서 화면에 보일 이름이 든 칸
 * @param autoSelectSingle 선택지가 하나뿐일 때 화면이 그것을 고르는가
 */
public record ConnectorFieldOptions(String tool, String items, String value, String label, boolean autoSelectSingle) {}
