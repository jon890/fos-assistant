package com.bifos.assistant.chat.artifact.infra;

import java.net.InetAddress;

public interface ArtifactSourceDnsResolver {
    InetAddress[] resolve(String host) throws Exception;
}
