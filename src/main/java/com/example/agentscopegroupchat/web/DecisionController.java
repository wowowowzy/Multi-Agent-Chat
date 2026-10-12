package com.example.agentscopegroupchat.web;

import com.example.agentscopegroupchat.dto.DecisionDto;
import com.example.agentscopegroupchat.store.DecisionLogDao;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 决策透视数据（5.3，D1）：某条消息触发的门控结果，谁想说话/表达欲/被刷掉/为何沉默。 */
@RestController
@RequestMapping("/api/decisions")
public class DecisionController {

    private final DecisionLogDao decisionLogDao;

    public DecisionController(DecisionLogDao decisionLogDao) {
        this.decisionLogDao = decisionLogDao;
    }

    @GetMapping
    public List<DecisionDto> byMessage(@RequestParam("messageId") long messageId) {
        return decisionLogDao.findByMessageId(messageId);
    }
}