package com.bifos.assistant.mcp.application.port;

/**
 * 에이전트의 실행 공간에서 파일 하나를 읽는 길이다(ADR-20261119 / file-space-put). 구현은 {@code workspace} 가 갖고 {@code mcp} 는
 * {@code workspace} 를 import 하지 않는다.
 */
public interface SandboxFiles {

    /**
     * 주인 디렉터리 안의 일반 파일 하나를 링크를 따라가지 않고 읽는다.
     *
     * @param sandboxOwner 실행 공간의 주인 키. {@code Agent.sandboxOwner()} 의 값이다
     * @param relativePath 실행 공간 안의 상대 경로
     * @param maxBytes 이보다 크면 읽지 않는다
     */
    SandboxFile read(String sandboxOwner, String relativePath, long maxBytes);


    /**
     * 읽은 결과다.
     *
     * @param bytes {@link Status#FOUND} 일 때만 채운다
     */
    record SandboxFile(Status status, byte[] bytes) {

        public static SandboxFile found(byte[] bytes) {
            return new SandboxFile(Status.FOUND, bytes);
        }


        public static SandboxFile of(Status status) {
            return new SandboxFile(status, null);
        }
    }

    enum Status {
        /** 읽었다. */
        FOUND,
        /** 없거나 폴더, 링크, 특수 파일이거나 읽을 수 없거나 경로 형식이 틀렸다. 실행 공간이 없는 것도 여기다. */
        MISSING,
        /** 상한을 넘는다. */
        TOO_LARGE
    }
}
