package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.application.AccountbookProperties;
import com.bifos.assistant.connector.application.AccountbookTokenVerifier;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.net.URI;
import java.net.HttpURLConnection;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
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
        List<FamilyOption> families = readFamilies(token);
        if (familyUuid != null && families.stream().noneMatch(family -> family.uuid().equals(familyUuid)))
            throw new ApiException(ErrorCode.ACCOUNTBOOK_FAMILY_FORBIDDEN, "the selected family is unavailable");
    }
    @Override public List<FamilyOption> readFamilies(String token) {
        if (!properties.configured()) throw new ApiException(ErrorCode.ACCOUNTBOOK_UNAVAILABLE, "accountbook connection is unavailable");
        try {
            JsonNode families = client.get().uri(URI.create(properties.apiBaseUrl().replaceAll("/$", "") + "/families"))
                    .header("Authorization", "Bearer " + token).retrieve().body(JsonNode.class);
            JsonNode data = families == null ? null : families.get("data");
            if (data == null || !data.isArray()) throw new ApiException(ErrorCode.ACCOUNTBOOK_UNAVAILABLE, "accountbook response is unavailable");
            List<FamilyOption> result = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode uuid = item.get("uuid");
                JsonNode name = item.get("name");
                if (uuid == null || !uuid.isTextual() || name == null || !name.isTextual() || name.asText().isBlank())
                    throw new ApiException(ErrorCode.ACCOUNTBOOK_UNAVAILABLE, "accountbook response is unavailable");
                UUID id = UUID.fromString(uuid.asText());
                if (!id.toString().equalsIgnoreCase(uuid.asText())) throw new IllegalArgumentException();
                result.add(new FamilyOption(id, name.asText()));
            }
            return List.copyOf(result);
        } catch (ApiException ex) { throw ex; }
        catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) throw new ApiException(ErrorCode.ACCOUNTBOOK_TOKEN_REJECTED, "accountbook token was rejected");
            throw new ApiException(ErrorCode.ACCOUNTBOOK_UNAVAILABLE, "accountbook connection is unavailable");
        } catch (RestClientException | IllegalArgumentException ex) { throw new ApiException(ErrorCode.ACCOUNTBOOK_UNAVAILABLE, "accountbook connection is unavailable"); }
    }
}
