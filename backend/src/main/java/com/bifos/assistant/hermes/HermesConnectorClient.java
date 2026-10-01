package com.bifos.assistant.hermes;

import java.util.List;

public interface HermesConnectorClient {
    record ConnectorState(String profile, boolean enabled, boolean configured, boolean restartRequired) {}

    record ProbeResult(boolean ok, List<String> tools) {}

    boolean putConnector(String profile, boolean enabled);

    ConnectorState readConnector(String profile);

    ProbeResult probeAccountbook(String profile);

    boolean putEnv(String profile, String key, String value);

    boolean deleteEnv(String profile, String key);
}
