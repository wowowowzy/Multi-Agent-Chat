package com.example.agentscopegroupchat.scheduler;

import com.example.agentscopegroupchat.dto.AgentDef;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** @提及解析（B1）：被 @ 的成员硬进候选，不受门控否决。 */
public final class MentionParser {

    private static final Pattern TOKEN = Pattern.compile("@([\\p{L}\\p{N}_\\-]{1,20})");

    private MentionParser() {
    }

    /** 返回被 @ 的 agent id 集合；支持 @all / @所有人。 */
    public static Set<String> mentionedAgentIds(String content, List<AgentDef> agents) {
        Set<String> ids = new HashSet<>();
        if (content == null || content.isBlank() || agents == null || agents.isEmpty()) {
            return ids;
        }
        if (content.contains("@all") || content.contains("@所有人") || content.contains("@全体成员")) {
            agents.forEach(a -> ids.add(a.getId()));
            return ids;
        }
        Matcher matcher = TOKEN.matcher(content);
        while (matcher.find()) {
            String token = matcher.group(1);
            for (AgentDef a : agents) {
                String name = a.getName();
                if (name == null) {
                    continue;
                }
                if (name.equals(token) || name.startsWith(token) || token.startsWith(name)) {
                    ids.add(a.getId());
                }
            }
        }
        return ids;
    }
}