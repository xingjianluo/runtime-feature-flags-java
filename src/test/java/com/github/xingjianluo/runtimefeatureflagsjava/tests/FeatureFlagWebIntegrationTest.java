package com.github.xingjianluo.runtimefeatureflagsjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 端到端：发布 → 判定 → 非法批次整批拒绝 → 回滚。
 * 纯本地 Spring Boot + MockMvc，无外部服务、无真实凭据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeatureFlagWebIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("完整生命周期：默认配置 -> 发布 -> 判定 ON -> 非法批次 422 不生效 -> 回滚 -> ROLLED_BACK")
    void fullLifecycleOverHttp() throws Exception {
        // 启动配置层 application.properties 中 page-size=15，优先于默认值 20
        mvc.perform(get("/api/configs/page-size"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("15"))
                .andExpect(jsonPath("$.layer").value("STARTUP"));

        // 整批发布：一个配置 + 一个满量开关
        mvc.perform(post("/api/feature-flags/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"configs":{"page-size":"42"},
                                 "flags":{"checkout-new":{"percent":100,"allowList":[],
                                 "denyList":[],"forceOn":[],"forceOff":[]}},
                                 "publishedBy":"tester","note":"http-e2e"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(true));

        mvc.perform(get("/api/feature-flags/evaluate").param("flag", "checkout-new").param("subject", "u-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON"))
                .andExpect(jsonPath("$.reason").isNotEmpty());

        // 非法批次：越界配置 + 互斥名单，必须 422 且 errorCode=BATCH_INVALID，线上保持不变
        mvc.perform(post("/api/feature-flags/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"configs":{"page-size":"999999"},
                                 "flags":{"broken":{"percent":50,"allowList":["x"],"denyList":["x"],
                                 "forceOn":[],"forceOff":[]}},
                                 "publishedBy":"tester","note":"bad"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("BATCH_INVALID"))
                .andExpect(jsonPath("$.details").isArray());

        // 非法批次未生效：配置仍为 42
        mvc.perform(get("/api/configs/page-size"))
                .andExpect(jsonPath("$.value").value("42"))
                .andExpect(jsonPath("$.layer").value("RUNTIME"));

        long currentVersion = Long.parseLong(mvc.perform(get("/api/feature-flags/version"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"version\":(\\d+).*", "$1"));

        // 回滚到版本 0：开关消失，再判定得到可区分的 ROLLED_BACK
        mvc.perform(post("/api/feature-flags/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetVersion\":0,\"publishedBy\":\"tester\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restoredFrom").value(0));

        mvc.perform(get("/api/feature-flags/evaluate").param("flag", "checkout-new").param("subject", "u-1"))
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"));

        // 从未存在过的开关仍是 UNKNOWN，不与回滚态混淆
        mvc.perform(get("/api/feature-flags/evaluate").param("flag", "never-existed").param("subject", "u-1"))
                .andExpect(jsonPath("$.status").value("UNKNOWN"));

        // 变更记录可查，且当前版本号比回滚前又前进了一步（回滚产生新版本）
        mvc.perform(get("/api/feature-flags/history").param("key", "checkout-new"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isArray());
    }
}
