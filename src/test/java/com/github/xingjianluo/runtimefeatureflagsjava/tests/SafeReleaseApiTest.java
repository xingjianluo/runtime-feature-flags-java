package com.github.xingjianluo.runtimefeatureflagsjava.tests;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
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
 * HTTP surface of the safe-release features: dry-run endpoint, expectedVersion
 * conflict (409 VERSION_CONFLICT), version diff endpoint, and backwards
 * compatibility of requests that omit expectedVersion. Fully in-process.
 * Version numbers are always read back from responses so tests are independent
 * of execution order and of other tests sharing the application context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SafeReleaseApiTest {

    private static final Logger log = LoggerFactory.getLogger(SafeReleaseApiTest.class);

    @Autowired
    private MockMvc mvc;

    private long currentVersion() throws Exception {
        MvcResult r = mvc.perform(get("/api/feature-flags/config")).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.version")).longValue();
    }

    private long publish(String jsonBody) throws Exception {
        MvcResult r = mvc.perform(post("/api/feature-flags/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(jsonBody))
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.version")).longValue();
    }

    @Test
    void dryRunEndpointValidatesAndPreviewsWithoutCommitting() throws Exception {
        long seeded = publish("{\"operator\":\"alice\",\"flags\":["
                + "{\"key\":\"seed\",\"enabled\":true,\"percentage\":100}]}");

        String dryRunBody = """
                {
                  "operator": "bob",
                  "flags": [{"key": "seed", "enabled": true, "percentage": 50}],
                  "config": [
                    {"key": "service.maxRetries", "value": "7"},
                    {"key": "service.timeoutMs", "value": "oops"}
                  ]
                }
                """;
        mvc.perform(post("/api/feature-flags/publish/dry-run")
                        .contentType(MediaType.APPLICATION_JSON).content(dryRunBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.baseVersion").value((int) seeded))
                .andExpect(jsonPath("$.errors[?(@.type == 'TYPE_MISMATCH')]").exists())
                .andExpect(jsonPath("$.changes[?(@.key == 'seed' && @.kind == 'CHANGED')]").exists())
                .andExpect(jsonPath("$.changes[?(@.key == 'service.maxRetries' && @.kind == 'ADDED')]").exists())
                .andDo(r -> log.info("[safe-release-api] dry-run -> {}", r.getResponse().getContentAsString()));

        // Nothing was committed: version did not move and the previewed config is absent.
        mvc.perform(get("/api/feature-flags/config"))
                .andExpect(jsonPath("$.version").value((int) seeded))
                .andExpect(jsonPath("$.values['service.maxRetries']").value(4)); // startup layer, not 7
        mvc.perform(get("/api/feature-flags/evaluate").param("key", "seed").param("subject", "u1"))
                .andExpect(jsonPath("$.version").value((int) seeded));
    }

    @Test
    void publishWithExpectedVersionConflictsAreDistinguishable() throws Exception {
        long current = currentVersion();

        // Pinning to a version that is no longer current -> 409 VERSION_CONFLICT.
        String staleBody = "{\"operator\":\"carol\",\"expectedVersion\":" + (current + 100) + ","
                + "\"flags\":[{\"key\":\"pinned\",\"enabled\":true,\"percentage\":100}]}";
        mvc.perform(post("/api/feature-flags/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(staleBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("VERSION_CONFLICT"))
                .andDo(r -> log.info("[safe-release-api] conflict -> {}", r.getResponse().getContentAsString()));

        // The rejected batch left no trace.
        mvc.perform(get("/api/feature-flags/evaluate").param("key", "pinned").param("subject", "u1"))
                .andExpect(jsonPath("$.status").value("UNKNOWN_FLAG"));

        // Pinning to the actual current version succeeds.
        String okBody = "{\"operator\":\"carol\",\"expectedVersion\":" + current + ","
                + "\"flags\":[{\"key\":\"pinned\",\"enabled\":true,\"percentage\":100}]}";
        long pinned = publish(okBody);
        log.info("[safe-release-api] pinned publish -> v{}", pinned);

        // Rollback pinned to a stale version -> 409 as well; nothing changes.
        mvc.perform(post("/api/feature-flags/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetVersion\":0,\"operator\":\"dave\",\"expectedVersion\":"
                                + (pinned + 100) + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("VERSION_CONFLICT"));
        mvc.perform(get("/api/feature-flags/config"))
                .andExpect(jsonPath("$.version").value((int) pinned));

        // Requests without expectedVersion behave exactly as before (no 409).
        long legacy = publish("{\"operator\":\"erin\",\"flags\":["
                + "{\"key\":\"legacy\",\"enabled\":true,\"percentage\":100}]}");
        log.info("[safe-release-api] legacy unpinned publish -> v{}", legacy);
    }

    @Test
    void diffEndpointReturnsRedactedChangesAndRejectsExpiredVersions() throws Exception {
        long before = publish("{\"operator\":\"frank\",\"flags\":["
                + "{\"key\":\"diff-base\",\"enabled\":true,\"percentage\":100}]}");
        long after = publish("{\"operator\":\"frank\",\"config\":[{\"key\":\"ui.theme\",\"value\":\"dark\"}]}");

        mvc.perform(get("/api/feature-flags/diff")
                        .param("from", String.valueOf(before)).param("to", String.valueOf(after)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[?(@.key == 'ui.theme' && @.kind == 'ADDED')]").exists())
                .andExpect(jsonPath("$.changes[?(@.afterSummary == 'dark')]").doesNotExist())
                .andDo(r -> log.info("[safe-release-api] diff -> {}", r.getResponse().getContentAsString()));

        // A version that never existed / has expired is a distinguishable 404.
        mvc.perform(get("/api/feature-flags/diff").param("from", "999999").param("to", String.valueOf(after)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("VERSION_NOT_FOUND"))
                .andDo(r -> log.info("[safe-release-api] expired diff -> {}", r.getResponse().getContentAsString()));
    }
}
