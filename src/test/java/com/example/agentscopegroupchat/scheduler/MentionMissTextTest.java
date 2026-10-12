package com.example.agentscopegroupchat.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * "被 @ 但没回你" 提示文案（前端实时反馈）单元测试。
 *
 * <p>两种未回复语义要分开措辞：装没看见（SILENT）与想接话但被配额刷掉（SKIPPED），
 * 别把"没抢到话头"也说成"不理你"。
 */
class MentionMissTextTest {

    @Test
    void silentMeansIgnored() {
        String t = GroupScheduler.mentionMissText("小元气", "SILENT");
        assertTrue(t.contains("小元气"), t);
        assertTrue(t.contains("没理睬"), t);
    }

    @Test
    void skippedMeansLostTheTurnNotIgnored() {
        String t = GroupScheduler.mentionMissText("热心大哥", "SKIPPED");
        assertTrue(t.contains("热心大哥"), t);
        assertTrue(t.contains("没抢到话头"), t);
        assertFalse(t.contains("没理睬"), t);
    }

    @Test
    void nullOutcomeFallsBackToIgnored() {
        assertTrue(GroupScheduler.mentionMissText("话痨阿强", null).contains("没理睬"));
    }
}