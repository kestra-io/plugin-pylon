package io.kestra.plugin.pylon.issue;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
@WireMockTest
class ListTest {
    @Inject
    private RunContextFactory runContextFactory;

    private List.ListBuilder<?, ?> task(WireMockRuntimeInfo wireMockRuntimeInfo) {
        return List.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"));
    }

    @Test
    void fetchReturnsAllRows(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(get(urlPathEqualTo("/issues")).willReturn(okJson("""
            {
              "data": [
                {"id": "issue-1", "number": 1, "title": "First"},
                {"id": "issue-2", "number": 2, "title": "Second"}
              ],
              "pagination": {"cursor": "", "has_next_page": false}
            }
            """)));

        var output = task(wireMockRuntimeInfo).fetchType(Property.ofValue(FetchType.FETCH)).build()
            .run(runContextFactory.of(Map.of()));

        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getSize(), is(2L));
        assertThat(output.getRows().get(0).get("title"), is("First"));
    }

    @Test
    void fetchOneReturnsFirstRowOnly(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(get(urlPathEqualTo("/issues")).willReturn(okJson("""
            {
              "data": [{"id": "issue-1", "number": 1, "title": "First"}],
              "pagination": {"cursor": "", "has_next_page": false}
            }
            """)));

        var output = task(wireMockRuntimeInfo).fetchType(Property.ofValue(FetchType.FETCH_ONE)).build()
            .run(runContextFactory.of(Map.of()));

        assertThat(output.getRow(), notNullValue());
        assertThat(output.getRow().get("title"), is("First"));
        assertThat(output.getSize(), is(1L));
    }

    @Test
    void fetchOneWithNoResultsReturnsNullRow(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(get(urlPathEqualTo("/issues")).willReturn(okJson("""
            {"data": [], "pagination": {"cursor": "", "has_next_page": false}}
            """)));

        var output = task(wireMockRuntimeInfo).fetchType(Property.ofValue(FetchType.FETCH_ONE)).build()
            .run(runContextFactory.of(Map.of()));

        assertThat(output.getRow(), is((Map<String, Object>) null));
        assertThat(output.getSize(), is(0L));
    }

    @Test
    void walksCursorPaginationAcrossMultiplePages(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(get(urlPathEqualTo("/issues")).withQueryParam("cursor", absent()).willReturn(okJson("""
            {
              "data": [{"id": "issue-1", "number": 1, "title": "First"}],
              "pagination": {"cursor": "page-2", "has_next_page": true}
            }
            """)));

        stubFor(get(urlPathEqualTo("/issues")).withQueryParam("cursor", equalTo("page-2")).willReturn(okJson("""
            {
              "data": [{"id": "issue-2", "number": 2, "title": "Second"}],
              "pagination": {"cursor": "", "has_next_page": false}
            }
            """)));

        var output = task(wireMockRuntimeInfo).fetchType(Property.ofValue(FetchType.FETCH)).build()
            .run(runContextFactory.of(Map.of()));

        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getSize(), is(2L));
    }

    @Test
    void storeStreamsRowsToInternalStorage(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(get(urlPathEqualTo("/issues")).willReturn(okJson("""
            {
              "data": [
                {"id": "issue-1", "number": 1, "title": "First"},
                {"id": "issue-2", "number": 2, "title": "Second"}
              ],
              "pagination": {"cursor": "", "has_next_page": false}
            }
            """)));

        var runContext = runContextFactory.of(Map.of());
        var output = task(wireMockRuntimeInfo).fetchType(Property.ofValue(FetchType.STORE)).build().run(runContext);

        assertThat(output.getUri(), notNullValue());
        assertThat(output.getSize(), is(2L));

        java.util.List<Map> rows;
        try (var inputStream = runContext.storage().getFile(output.getUri());
             var reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            rows = FileSerde.readAll(reader, Map.class).collectList().block();
        }
        assertThat(rows, hasSize(2));
    }
}
