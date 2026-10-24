package com.dartwatch.opendart;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static com.dartwatch.opendart.OpenDartException.Kind.*;

public class OpenDartClient {
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private final RestClient restClient;
    private final OpenDartProperties properties;
    private final CorpCodeParser corpCodeParser;
    private final ObjectMapper objectMapper;

    public OpenDartClient(RestClient restClient, OpenDartProperties properties,
                          CorpCodeParser corpCodeParser, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.corpCodeParser = corpCodeParser;
        this.objectMapper = objectMapper;
    }

    public List<CorpRecord> downloadCompanies() {
        return corpCodeParser.parse(request("/corpCode.xml", null, null, null, 0));
    }

    public List<DartDisclosure> findDisclosures(String corpCode, LocalDate from, LocalDate through) {
        if (corpCode == null || !corpCode.matches("[0-9]{8}") || from == null || through == null || from.isAfter(through)) {
            throw new IllegalArgumentException("Invalid disclosure query");
        }
        List<DartDisclosure> all = new ArrayList<>();
        var receipts = new HashSet<String>();
        int expectedPages = -1;
        int expectedCount = -1;
        for (int page = 1; page <= properties.getMaxPages(); page++) {
            DisclosurePage response;
            try {
                response = objectMapper.readValue(request("/list.json", corpCode, from, through, page), DisclosurePage.class);
            } catch (IOException ex) {
                throw new OpenDartException(INVALID_RESPONSE);
            }
            if (response == null) throw new OpenDartException(INVALID_RESPONSE);
            if (OpenDartException.isEmpty(response.status(), response.message())) {
                if (page != 1) throw new OpenDartException(INVALID_RESPONSE);
                return List.of();
            }
            if (page == 1) {
                expectedPages = response.totalPage();
                expectedCount = response.totalCount();
            }
            if (expectedPages < 1 || expectedPages > properties.getMaxPages() || expectedCount < 1
                    || expectedCount > (long) properties.getMaxPages() * properties.getPageSize()
                    || expectedPages != (expectedCount + properties.getPageSize() - 1) / properties.getPageSize()
                    || response.pageNo() != page || response.pageCount() != properties.getPageSize()
                    || response.totalPage() != expectedPages || response.totalCount() != expectedCount
                    || response.list() == null || response.list().isEmpty() || response.list().size() > properties.getPageSize()) {
                throw new OpenDartException(INVALID_RESPONSE);
            }
            for (var disclosure : response.list()) {
                if (disclosure == null) throw new OpenDartException(INVALID_RESPONSE);
                disclosure.validate(corpCode, from, through);
                if (!receipts.add(disclosure.rceptNo())) throw new OpenDartException(INVALID_RESPONSE);
                all.add(disclosure);
            }
            if (page == expectedPages) {
                if (all.size() != expectedCount) throw new OpenDartException(INVALID_RESPONSE);
                return List.copyOf(all);
            }
        }
        throw new OpenDartException(INVALID_RESPONSE);
    }

    private byte[] request(String path, String corpCode, LocalDate from, LocalDate through, int page) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) throw new OpenDartException(CONFIGURATION);
        try {
            return restClient.get().uri(builder -> {
                builder.path(path).queryParam("crtfc_key", properties.getApiKey());
                if (corpCode != null) {
                    builder.queryParam("corp_code", corpCode)
                            .queryParam("bgn_de", from.format(DateTimeFormatter.BASIC_ISO_DATE))
                            .queryParam("end_de", through.format(DateTimeFormatter.BASIC_ISO_DATE))
                            .queryParam("page_no", page).queryParam("page_count", properties.getPageSize())
                            .queryParam("last_reprt_at", "N").queryParam("sort", "date").queryParam("sort_mth", "asc");
                }
                return builder.build();
            }).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) throw new OpenDartException(HTTP);
                byte[] body = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (body.length == 0 || body.length > MAX_RESPONSE_BYTES) throw new OpenDartException(INVALID_RESPONSE);
                return body;
            });
        } catch (OpenDartException ex) {
            throw ex;
        } catch (ResourceAccessException ex) {
            Throwable current = ex;
            while (current != null) {
                if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException) {
                    throw new OpenDartException(TIMEOUT);
                }
                current = current.getCause();
            }
            throw new OpenDartException(NETWORK);
        } catch (RestClientException ex) {
            throw new OpenDartException(NETWORK);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DisclosurePage(String status, String message, @JsonProperty("page_no") int pageNo,
                          @JsonProperty("page_count") int pageCount, @JsonProperty("total_count") int totalCount,
                          @JsonProperty("total_page") int totalPage, List<DartDisclosure> list) {
    }
}
