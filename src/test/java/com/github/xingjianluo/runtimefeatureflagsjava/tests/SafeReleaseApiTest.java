package com.github.xingjianluo.runtimefeatureflagsjava.tests;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP-level safe-release behavior: precheck endpoint, optimistic-concurrency publish,
 * and the redacted diff endpoint. Dirties the shared context afterwards so the
 * pre-existing API tests keep observing their own version numbering.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SafeReleaseApiTest {

    private static final Logger log = LoggerFactory.getLogger(SafeReleaseApiTest.class);

    @Autowired
    private MockMvc mvc;

    @Test
    @Order(1)
    void precheckValidatesAndPreviewsWithoutAdvancingVersion() throws Exception {
        // Baseline version before anything happens in this class.
        MvcResult configBefore = mvc.perform(get("/api/feature-flags/config"))
                .andExpect(status().isOk()).andReturn();
        String before = configBefore.getResponse().getContentAsString();
        log.info("[api-precheck] config before -> {}", before);

        // One valid entry + four differently-invalid entries in a single candidate batch.
        String body = """
                {
                  "operator": "bob",
                  "reason": "candidate release",
                  "flags": [{"key": "precheck-flag", "enabled": true, "percentage": 25}],
                  "config": [
                    {"key": "service.maxRetries", "value": "abc"},
                    {"key": "service.timeoutMs", "value": "99999999"},
                    {"key": "no.such.key", "value": "x"},
                    {"key": "payment.mode", "value": ""}
                  ]
                }
                """;
        mvc.perform(post("/api/feature-flags/precheck")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[?(@.category == 'TYPE_MISMATCH')]").exists())
                .andExpect(jsonPath("$.issues[?(@.category == 'CONSTRAINT_VIOLATION')]").exists())
                .andExpect(jsonPath("$.issues[?(@.category == 'UNKNOWN_CONFIG_KEY')]").exists())
                .andExpect(jsonPath("$.issues[?(@.category == 'MISSING_REQUIRED_VALUE')]").exists())
                .andExpect(jsonPath("$.changes[?(@.key == 'precheck-flag' && @.kind == 'ADDED')]").exists())
                .andDo(r -> log.info("[api-precheck] invalid candidate -> {}",
                        r.getResponse().getContentAsString()));

        // Read-only: version did not advance, flag did not appear, config untouched.
        mvc.perform(get("/api/feature-flags/config"))
                .andExpect(status().isOk())
                .andExpect(content().json(before));
        mvc.perform(get("/api/feature-flags/evaluate")
                        .param("key", "precheck-flag").param("subject", "u1"))
                .andExpect(jsonPath("$.status").value("UNKNOWN_FLAG"));
    }

    @Test
    @Order(2)
    void publishWithExpectedBaseVersionConflictThenSuccess() throws Exception {
        // A clean candidate that passes precheck; remember the base version it saw.
        String candidate = """
                {
                  "operator": "bob",
                  "flags": [{"key": "pinned-flag", "enabled": true, "percentage": 100}],
                  "config": [{"key": "service.maxRetries", "value": "6"}]
                }
                """;
        MvcResult precheck = mvc.perform(post("/api/feature-flags/precheck")
                        .contentType(MediaType.APPLICATION_JSON).content(candidate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andDo(r -> log.info("[api-conflict] precheck -> {}",
                        r.getResponse().getContentAsString()))
                .andReturn();
        long baseVersion = ((Number) com.jayway.jsonpath.JsonPath
                .read(precheck.getResponse().getContentAsString(), "$.baseVersion")).longValue();

        // Someone else commits first, moving the current version past the observed base.
        mvc.perform(post("/api/feature-flags/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"carol\",\"flags\":[{\"key\":\"other-flag\",\"percentage\":100}]}"))
                .andExpect(status().isOk());

        // Submitting pinned to the stale base -> 409 VERSION_CONFLICT, nothing applied.
        String pinned = candidate.replace("\"operator\": \"bob\"",
                "\"operator\": \"bob\", \"expectedBaseVersion\": " + baseVersion);
        mvc.perform(post("/api/feature-flags/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(pinned))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("VERSION_CONFLICT"))
                .andDo(r -> log.info("[api-conflict] stale pinned publish -> {}",
                        r.getResponse().getContentAsString()));
        mvc.perform(get("/api/feature-flags/evaluate")
                        .param("key", "pinned-flag").param("subject", "u1"))
                .andExpect(jsonPath("$.status").value("UNKNOWN_FLAG"));

        // Re-pinned to the real current version -> whole batch applies.
        String repinned = candidate.replace("\"operator\": \"bob\"",
                "\"operator\": \"bob\", \"expectedBaseVersion\": " + (baseVersion + 1));
        mvc.perform(post("/api/feature-flags/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(repinned))
                .andExpect(status().isOk())
                .andDo(r -> log.info("[api-conflict] re-pinned publish -> {}",
                        r.getResponse().getContentAsString()));
        mvc.perform(get("/api/feature-flags/evaluate")
                        .param("key", "pinned-flag").param("subject", "u1"))
                .andExpect(jsonPath("$.status").value("ENABLED"));
    }

    @Test
    @Order(3)
    void diffEndpointReturnsRedactedChangesAndDistinguishesEvictedVersions() throws Exception {
        MvcResult history = mvc.perform(get("/api/feature-flags/history"))
                .andExpect(status().isOk()).andReturn();
        java.util.List<Number> versions = com.jayway.jsonpath.JsonPath
                .read(history.getResponse().getContentAsString(), "$[*].version");
        long newest = versions.stream().mapToLong(Number::longValue).max().orElseThrow();
        long oldest = versions.stream().mapToLong(Number::longValue).min().orElseThrow();
        log.info("[api-diff] retained versions={}", versions);

        mvc.perform(get("/api/feature-flags/diff")
                        .param("from", String.valueOf(oldest))
                        .param("to", String.valueOf(newest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isArray())
                .andExpect(jsonPath("$.entries[?(@.afterDigest == '6')]").doesNotExist())
                .andDo(r -> log.info("[api-diff] v{}->v{} -> {}", oldest, newest,
                        r.getResponse().getContentAsString()));

        // A version long evicted from the bounded history -> 404 VERSION_NOT_FOUND.
        mvc.perform(get("/api/feature-flags/diff")
                        .param("from", "999999")
                        .param("to", String.valueOf(newest)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("VERSION_NOT_FOUND"))
                .andDo(r -> log.info("[api-diff] unknown version -> {}",
                        r.getResponse().getContentAsString()));
    }
}
