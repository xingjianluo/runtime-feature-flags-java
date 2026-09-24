package com.github.xingjianluo.runtimefeatureflagsjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** End-to-end HTTP behavior; fully in-process, no external services or real credentials. */
@SpringBootTest
@AutoConfigureMockMvc
class FeatureFlagApiTest {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagApiTest.class);

    @Autowired
    private MockMvc mvc;

    @Test
    void publishEvaluateRollbackAndInspectHistory() throws Exception {
        // Unknown flag first.
        mvc.perform(get("/api/feature-flags/evaluate").param("key", "checkout").param("subject", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNKNOWN_FLAG"))
                .andDo(r -> log.info("[api] unknown evaluate -> {}", r.getResponse().getContentAsString()));

        // Publish a 100% flag atomically.
        String body = """
                {
                  "operator": "alice",
                  "reason": "launch checkout rollout",
                  "flags": [
                    {"key": "checkout", "enabled": true, "percentage": 100}
                  ],
                  "config": [
                    {"key": "service.maxRetries", "value": "5"}
                  ]
                }
                """;
        mvc.perform(post("/api/feature-flags/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andDo(r -> log.info("[api] publish -> {}", r.getResponse().getContentAsString()));

        mvc.perform(get("/api/feature-flags/evaluate").param("key", "checkout").param("subject", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENABLED"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.version").value(1));

        // Merged config snapshot reflects the runtime override (5) over the startup value (4).
        mvc.perform(get("/api/feature-flags/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values['service.maxRetries']").value(5))
                .andDo(r -> log.info("[api] config -> {}", r.getResponse().getContentAsString()));

        // Rollback to bootstrap (v0).
        mvc.perform(post("/api/feature-flags/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetVersion\":0,\"operator\":\"bob\",\"reason\":\"undo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/api/feature-flags/evaluate").param("key", "checkout").param("subject", "u1"))
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"))
                .andDo(r -> log.info("[api] after rollback -> {}", r.getResponse().getContentAsString()));

        // History and changes are present and redacted (no raw config value).
        mvc.perform(get("/api/feature-flags/history"))
                .andExpect(status().isOk())
                .andDo(r -> log.info("[api] history -> {}", r.getResponse().getContentAsString()));
        mvc.perform(get("/api/feature-flags/changes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.redactedValue == '5')]").doesNotExist())
                .andDo(r -> log.info("[api] changes -> {}", r.getResponse().getContentAsString()));
    }

    @Test
    void invalidBatchIsRejectedWithDistinguishableErrorAndNoVersionChange() throws Exception {
        // maxRetries constrained to <= 10: 99 is illegal -> whole batch rejected (422 INVALID_BATCH).
        String body = """
                {
                  "operator": "alice",
                  "flags": [{"key": "sibling-flag", "enabled": true, "percentage": 100}],
                  "config": [{"key": "service.maxRetries", "value": "99"}]
                }
                """;
        mvc.perform(post("/api/feature-flags/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("INVALID_BATCH"))
                .andDo(r -> log.info("[api] invalid batch -> {}", r.getResponse().getContentAsString()));

        // Sibling flag must not exist (no partial update).
        mvc.perform(get("/api/feature-flags/evaluate")
                        .param("key", "sibling-flag").param("subject", "u1"))
                .andExpect(jsonPath("$.status").value("UNKNOWN_FLAG"));
    }

    @Test
    void badTypeAndMissingVersionHaveDistinctStatusCodes() throws Exception {
        // Type mismatch -> 400 TYPE_MISMATCH.
        String body = """
                {"operator":"alice","config":[{"key":"service.timeoutMs","value":"not-a-number"}]}
                """;
        mvc.perform(post("/api/feature-flags/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("INVALID_BATCH"))
                .andDo(r -> log.info("[api] bad type within batch -> {}", r.getResponse().getContentAsString()));

        // Rollback to an unretained/unknown version -> 404 VERSION_NOT_FOUND.
        mvc.perform(post("/api/feature-flags/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetVersion\":999999,\"operator\":\"bob\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("VERSION_NOT_FOUND"))
                .andDo(r -> log.info("[api] unknown version -> {}", r.getResponse().getContentAsString()));
    }
}
