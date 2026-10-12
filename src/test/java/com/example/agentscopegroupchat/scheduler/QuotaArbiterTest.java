package com.example.agentscopegroupchat.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 50% 配额裁决（A4）单元测试。 */
class QuotaArbiterTest {

    @Test
    void quotaIsCeilingOfPercent() {
        assertEquals(3, QuotaArbiter.quota(5, 50)); // ceil(2.5)
        assertEquals(2, QuotaArbiter.quota(4, 50));
        assertEquals(2, QuotaArbiter.quota(3, 50)); // ceil(1.5)
        assertEquals(0, QuotaArbiter.quota(0, 50));
        assertEquals(5, QuotaArbiter.quota(5, 100));
        assertEquals(0, QuotaArbiter.quota(5, 0));
    }

    @Test
    void selectsAtMostQuota() {
        List<Integer> candidates = new ArrayList<>(List.of(9, 8, 7, 6, 5));
        List<Integer> selected = QuotaArbiter.select(candidates, Integer::intValue, 2, new Random(1));
        assertEquals(2, selected.size());
        assertTrue(selected.containsAll(List.of(9, 8)));
    }

    @Test
    void closeScoresWithinOneAreShuffledButDistantOnesAreStable() {
        // 表达欲差距 >= 2 时顺序确定
        List<Integer> distinct = List.of(5, 7, 9);
        List<Integer> picked = QuotaArbiter.select(distinct, Integer::intValue, 1, new Random(7));
        assertEquals(List.of(9), picked);

        // 并列（差值 <= 1）时允许随机洗牌，但入选者必须在并列集合内
        List<Integer> tied = List.of(9, 9, 9, 1);
        for (int seed = 0; seed < 20; seed++) {
            List<Integer> top2 = QuotaArbiter.select(tied, Integer::intValue, 2, new Random(seed));
            assertEquals(2, top2.size());
            assertTrue(top2.stream().allMatch(v -> v == 9));
        }
    }

    @Test
    void emptyOrZeroQuotaYieldsNothing() {
        assertTrue(QuotaArbiter.select(List.of(1, 2), Integer::intValue, 0, new Random(1)).isEmpty());
        assertTrue(QuotaArbiter.select(List.of(), Integer::intValue, 3, new Random(1)).isEmpty());
    }
}