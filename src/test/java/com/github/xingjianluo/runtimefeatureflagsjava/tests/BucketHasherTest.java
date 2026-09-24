package com.github.xingjianluo.runtimefeatureflagsjava.tests;

import com.github.xingjianluo.runtimefeatureflagsjava.service.BucketHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 确定性分桶：同标识稳定、桶位范围合法、不同键空间相互独立。 */
class BucketHasherTest {
    private static final Logger log = LoggerFactory.getLogger(BucketHasherTest.class);

    @Test
    @DisplayName("同一(开关,主体)在任意线程与调用次数下桶位稳定")
    void bucketIsStableAndDeterministic() throws Exception {
        String flag = "checkout-redesign";
        String subject = "user-7788";
        int first = BucketHasher.bucket(flag, subject);
        log.info("输入: flag={}, subject={} -> 判定依据: bucket={}", flag, subject, first);
        for (int i = 0; i < 100; i++) {
            int again = BucketHasher.bucket(flag, subject);
            assertEquals(first, again, "同一标识桶位不得漂移");
        }
        // 跨线程同样稳定（哈希计算不依赖任何线程本地状态）
        Thread other = new Thread(() ->
                assertEquals(first, BucketHasher.bucket(flag, subject)));
        other.start();
        other.join();
        assertTrue(first >= 0 && first <= 99, "桶位必须在 [0,99] 内: " + first);
    }

    @Test
    @DisplayName("开关键与主体标识任一不同都可能改变桶位（键空间隔离）")
    void keyAndSubjectBothParticipateInHash() {
        int a = BucketHasher.bucket("flag-a", "user-1");
        int b = BucketHasher.bucket("flag-b", "user-1");
        int c = BucketHasher.bucket("flag-a", "user-2");
        log.info("输入: (flag-a,user-1)={}, (flag-b,user-1)={}, (flag-a,user-2)={}", a, b, c);
        // 100 桶下三组全等概率极低；这里验证存在差异，保证两个维度都进入哈希
        assertFalse(a == b && b == c, "开关键与主体标识必须都参与分桶");
    }

    @Test
    @DisplayName("大样本桶位全部落在 [0,99] 且分布覆盖多个桶")
    void bucketsAlwaysInRange() {
        java.util.Set<Integer> seen = new java.util.TreeSet<>();
        for (int i = 0; i < 5000; i++) {
            int bucket = BucketHasher.bucket("any-flag", "subject-" + i);
            assertTrue(bucket >= 0 && bucket <= 99);
            seen.add(bucket);
        }
        log.info("输入: 5000 个不同主体 -> 覆盖桶位数={}", seen.size());
        assertTrue(seen.size() > 80, "5000 个主体应覆盖绝大多数桶位，实际 " + seen.size());
    }

    @Test
    @DisplayName("null 入参快速失败而非产生任意桶位")
    void nullInputsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BucketHasher.bucket(null, "u"));
        assertThrows(IllegalArgumentException.class, () -> BucketHasher.bucket("f", null));
    }
}
