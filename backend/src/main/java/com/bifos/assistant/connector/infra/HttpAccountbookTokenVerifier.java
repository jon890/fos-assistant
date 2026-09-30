package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.application.AccountbookProperties;
import com.bifos.assistant.connector.application.AccountbookTokenVerifier;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.net.URI;
import java.net.HttpURLConnection;
import java.util.UUID;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

@Component
public class HttpAccountbookTokenVerifier implements AccountbookTokenVerifier {
    private final AccountbookProperties properties;
    private final RestClient client;
    public HttpAccountbookTokenVerifier(AccountbookProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws java.io.IOException {
                super.prepareConnection(connection, method); connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(properties.connectTimeout()); factory.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder().requestFactory(factory).build();
    }
    @Override public void verify(String token, UUID familyUuid) {
        if (!properties.configured()) throw new ApiException(ErrorCode.ACCOUNTBOOK_UNAVAILABLE, "accountbook connection is unavailable");
        try {
            JsonNode families = client.get().uri(URI.create(properties.apiBaseUrl().replaceAll("/$", "") + "/families"))
                    .header("Authorization", "Bearer " + token).retrieve().body(JsonNode.class);
            JsonNode data = families == null ? null : families.get("data");
            if (data == null || !data.isArray()) throw new ApiException(ErrorCode.ACCOUNTBOOK_UNAVAILABLE, "accountbook response is unavailable");
            if (familyUuid != null && !containsFamily(data, familyUuid))
                throw new ApiException(ErrorCode.ACCOUNTBOOK_FAMILY_FORBIDDEN, "the selected family is unavailable");
        } catch (ApiException ex) { throw ex; }
        catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) throw new ApiException(ErrorCode.ACCOUNTBOOK_TOKEN_REJECTED, "accountbook token was rejected");
            throw new ApiException(ErrorCode.ACCOUNTBOOK_UNAVAILABLE, "accountbook connection is unavailable");
        } catch (RestClientException ex) { throw new ApiException(ErrorCode.ACCOUNTBOOK_UNAVAILABLE, "accountbook connection is unavailable"); }
    }
    private static boolean containsFamily(JsonNode values, UUID familyUuid) {
        if (values == null || !values.isArray()) return false;
        for (JsonNode item : values) {
            JsonNode uuid = item.get("uuid");
            if (uuid != null && uuid.isTextual() && familyUuid.toString().equals(uuid.asText())) return true;
        }
        return false;
    }
}
