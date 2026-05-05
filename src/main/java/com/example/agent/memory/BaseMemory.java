package com.example.agent.memory;

import java.util.List;
import java.util.Map;

/**
 * 记忆系统公共接口。
 * WorkingMemory / EpisodicMemory / SemanticMemory / PerceptualMemory 均实现此接口。
 */
public interface BaseMemory {

    /** 添加一条记忆，返回记忆 ID */
    String add(MemoryManager.MemoryItem item);

    /** 关键词检索（默认 limit=5） */
    List<MemoryManager.MemoryItem> retrieve(String query, int limit);

    /** 带过滤条件的检索 */
    List<MemoryManager.MemoryItem> retrieve(String query, int limit, Map<String, Object> kwargs);

    /** 当前存储的记忆数量 */
    int size();

    /** 移除指定 ID 的记忆（从向量存储、图谱存储、文档存储中彻底清理） */
    void remove(String id);

    /** 清空所有记忆 */
    void clear();
}
