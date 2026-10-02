package com.bifos.assistant.chat.infra;

import java.net.InetAddress;

public interface ArtifactSourceDnsResolver {
    InetAddress[] resolve(String host) throws Exception;
}
