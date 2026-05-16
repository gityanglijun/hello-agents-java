package com.cybertown.service;

import com.cybertown.agent.NpcBatchGenerator;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * NPC 状态管理器 — 对应 Python state_manager.py。
 *
 * 定时批量生成 NPC 对话（降低 API 成本），缓存当前 NPC 状态，提供状态查询接口。
 */
public class StateManager {

    private static StateManager instance;

    public static StateManager getInstance(int updateInterval) {
        if (instance == null) {
            instance = new StateManager(updateInterval);
        }
        return instance;
    }

    public static StateManager getInstance() {
        return getInstance(30);
    }

    private final int updateInterval;
    private final NpcBatchGenerator batchGenerator;
    private final ScheduledExecutorService scheduler;

    private Map<String, String> currentDialogues = new LinkedHashMap<>();
    private LocalDateTime lastUpdate;

    // ==================== 构造 ====================

    public StateManager(int updateInterval) {
        this.updateInterval = updateInterval;
        this.batchGenerator = NpcBatchGenerator.getInstance();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "npc-state-updater");
            t.setDaemon(true);
            return t;
        });
        System.out.println("📊 NPC状态管理器初始化完成 (更新间隔: " + updateInterval + "秒)");
    }

    // ==================== 生命周期 ====================

    /** 启动后台定时更新 */
    public void start() {
        System.out.println("🚀 启动NPC状态自动更新...");

        // 立即执行一次
        updateNpcStates();

        // 定时执行
        scheduler.scheduleWithFixedDelay(
                this::updateNpcStates,
                updateInterval,
                updateInterval,
                TimeUnit.SECONDS
        );
    }

    /** 停止后台更新 */
    public void stop() {
        scheduler.shutdownNow();
        System.out.println("🛑 NPC状态自动更新已停止");
    }

    // ==================== 核心更新 ====================

    private void updateNpcStates() {
        try {
            String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            System.out.println("\n🔄 [" + timeStr + "] 开始批量更新NPC对话...");

            Map<String, String> newDialogues = batchGenerator.generateBatchDialogues();

            this.currentDialogues = newDialogues;
            this.lastUpdate = LocalDateTime.now();

            System.out.println("📝 NPC对话已更新:");
            for (var entry : newDialogues.entrySet()) {
                System.out.println("   - " + entry.getKey() + ": " + entry.getValue());
            }

        } catch (Exception e) {
            System.err.println("❌ 更新NPC状态失败: " + e.getMessage());
        }
    }

    // ==================== 查询接口 ====================

    /** 获取当前状态 — 对应 Python get_current_state() */
    public Map<String, Object> getCurrentState() {
        int nextUpdateIn;
        if (lastUpdate != null) {
            long elapsed = java.time.Duration.between(lastUpdate, LocalDateTime.now()).getSeconds();
            nextUpdateIn = Math.max(0, (int) (updateInterval - elapsed));
        } else {
            nextUpdateIn = updateInterval;
        }

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("dialogues", currentDialogues);
        state.put("last_update", lastUpdate != null ? lastUpdate.toString() : null);
        state.put("next_update_in", nextUpdateIn);
        return state;
    }

    /** 获取指定 NPC 的当前对话 */
    public String getNpcDialogue(String npcName) {
        return currentDialogues.getOrDefault(npcName, "");
    }

    /** 强制立即更新 */
    public void forceUpdate() {
        System.out.println("⚡ 强制更新NPC状态...");
        updateNpcStates();
    }
}
