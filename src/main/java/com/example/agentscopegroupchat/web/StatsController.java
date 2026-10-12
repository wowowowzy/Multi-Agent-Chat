package com.example.agentscopegroupchat.web;

import com.example.agentscopegroupchat.dto.TokenStatsDto;
import com.example.agentscopegroupchat.store.TokenUsageDao;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Token / 成本统计（5.3，D2）：多群版——不带 groupId 看全部群聊总览（含按群聚合），带 groupId 看群内按人明细。 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final TokenUsageDao tokenUsageDao;

    public StatsController(TokenUsageDao tokenUsageDao) {
        this.tokenUsageDao = tokenUsageDao;
    }

    @GetMapping("/tokens")
    public TokenStatsDto tokens(@RequestParam(name = "groupId", required = false) String groupId) {
        return tokenUsageDao.stats(groupId);
    }
}