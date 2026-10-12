package com.example.agentscopegroupchat.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.agentscopegroupchat.dto.AgentDef;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** @提及解析（B1）单元测试。 */
class MentionParserTest {

    private static AgentDef agent(String id, String name) {
        AgentDef a = new AgentDef();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private final List<AgentDef> agents = List.of(
            agent("genki", "小元气"),
            agent("brother", "热心大哥"),
            agent("lurker", "潜水员阿呆"));

    @Test
    void exactMentionIsFound() {
        Set<String> ids = MentionParser.mentionedAgentIds("早上好 @小元气 在吗", agents);
        assertEquals(Set.of("genki"), ids);
    }

    @Test
    void multipleMentionsAreFound() {
        Set<String> ids = MentionParser.mentionedAgentIds("@小元气 @热心大哥 出来聊聊", agents);
        assertEquals(Set.of("genki", "brother"), ids);
    }

    @Test
    void partialTokenMatchesByPrefix() {
        // "@热心" 是 "热心大哥" 的前缀
        Set<String> ids = MentionParser.mentionedAgentIds("@热心 帮个忙", agents);
        assertTrue(ids.contains("brother"));
    }

    @Test
    void mentionAllCoversEveryone() {
        assertEquals(Set.of("genki", "brother", "lurker"),
                MentionParser.mentionedAgentIds("@所有人 开会了", agents));
        assertEquals(Set.of("genki", "brother", "lurker"),
                MentionParser.mentionedAgentIds("@all ping", agents));
    }

    @Test
    void noMentionYieldsEmpty() {
        assertTrue(MentionParser.mentionedAgentIds("今天天气不错", agents).isEmpty());
        assertTrue(MentionParser.mentionedAgentIds(null, agents).isEmpty());
        assertTrue(MentionParser.mentionedAgentIds("@小元气", List.of()).isEmpty());
    }
}