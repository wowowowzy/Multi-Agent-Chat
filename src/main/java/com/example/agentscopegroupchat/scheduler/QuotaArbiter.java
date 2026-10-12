package com.example.agentscopegroupchat.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.ToIntFunction;

/**
 * 50% 配额裁决（A4）。
 *
 * <p>想说话人数 ≤ 配额 → 全部放行；超额 → 按表达欲降序取前配额。
 * 分数接近（差值 ≤ 1）时随机洗牌——用 &lt;1 的随机抖动实现，模拟"手慢没抢到"。
 */
public final class QuotaArbiter {

    private QuotaArbiter() {
    }

    /** 单轮发言配额 = ceil(agentCount × percent%)。 */
    public static int quota(int agentCount, int percent) {
        if (agentCount <= 0) {
            return 0;
        }
        int p = Math.max(0, Math.min(100, percent));
        return (int) Math.ceil(agentCount * p / 100.0);
    }

    /**
     * 按表达欲挑选不超过 quota 个候选。
     *
     * @param candidates 候选项列表
     * @param eagerness  取表达欲分数（0~10）
     */
    public static <T> List<T> select(List<T> candidates, ToIntFunction<T> eagerness, int quota, Random random) {
        if (candidates.isEmpty() || quota <= 0) {
            return List.of();
        }
        record Scored<T>(T item, double score) {
        }
        List<Scored<T>> scored = new ArrayList<>(candidates.size());
        for (T c : candidates) {
            // 抖动 < 1：表达欲差距 ≥2 时顺序稳定，差值 ≤1 时随机洗牌
            scored.add(new Scored<>(c, eagerness.applyAsInt(c) + random.nextDouble() * 0.999));
        }
        scored.sort(Comparator.comparingDouble(Scored<T>::score).reversed());
        List<T> out = new ArrayList<>(Math.min(quota, scored.size()));
        for (int i = 0; i < scored.size() && i < quota; i++) {
            out.add(scored.get(i).item());
        }
        return out;
    }
}