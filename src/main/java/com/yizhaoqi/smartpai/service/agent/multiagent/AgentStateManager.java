package com.yizhaoqi.smartpai.service.agent.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.dto.SearchResult;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Agent 共享状态管理器
 *
 * 核心职责：
 * 多个 RetrievalAgent 并行执行后，需要将各自的检索结果写回到 Redis 共享状态树。
 * 若不加锁，并发写入会导致 Redis 的 List/Hash 操作出现竞态条件，
 * 部分结果被覆盖，汇总 Agent 永远等不到所有子任务完成。
 *
 * 解决方案：
 * 使用 Redisson 分布式锁（RLock）保护每次写回操作的原子性。
 * 锁粒度为"主任务级别"（correlationId），不同主任务互不阻塞。
 *
 * 状态结构（Redis Hash）：
 * Key: agent:task:{correlationId}
 * Field: results        → JSON 序列化的 List<AgentTaskResult>
 * Field: completedCount → 已完成子任务数
 * Field: totalCount     → 总子任务数
 * Field: status         → PENDING | PARTIAL | READY | DONE
 */
@Component
public class AgentStateManager {

    private static final Logger log = LoggerFactory.getLogger(AgentStateManager.class);

    private static final String KEY_PREFIX = "agent:task:";
    private static final String LOCK_PREFIX = "agent:lock:";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final long LOCK_WAIT_SECONDS = 5;
    private static final long LOCK_LEASE_SECONDS = 10;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 初始化主任务状态
     */
    public void initTask(String correlationId, int totalSubTasks) {
        String key = KEY_PREFIX + correlationId;
        redisTemplate.opsForHash().put(key, "totalCount", String.valueOf(totalSubTasks));
        redisTemplate.opsForHash().put(key, "completedCount", "0");
        redisTemplate.opsForHash().put(key, "status", "PENDING");
        redisTemplate.opsForHash().put(key, "results", "[]");
        redisTemplate.expire(key, STATE_TTL);
        log.info("[AgentStateManager] 初始化任务状态: correlationId={}, totalSubTasks={}", correlationId, totalSubTasks);
    }

    /**
     * 写回子任务结果（加 Redisson 分布式锁）
     *
     * 锁粒度：主任务级别（correlationId），不同主任务并行不阻塞。
     * 同一主任务的多个子任务结果写回时串行化，保证 completedCount 准确。
     *
     * @return true 表示所有子任务已完成，可以触发汇总
     */
    public boolean appendResult(String correlationId, AgentTaskResult result) {
        String lockKey = LOCK_PREFIX + correlationId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[AgentStateManager] 获取锁超时: correlationId={}", correlationId);
                return false;
            }

            try {
                return doAppendResult(correlationId, result);
            } finally {
                lock.unlock();
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[AgentStateManager] 锁等待被中断: {}", e.getMessage());
            return false;
        }
    }

    private boolean doAppendResult(String correlationId, AgentTaskResult result) {
        String key = KEY_PREFIX + correlationId;

        try {
            // 读取现有结果列表
            String existingJson = (String) redisTemplate.opsForHash().get(key, "results");
            List<AgentTaskResult> results = parseResults(existingJson);
            results.add(result);

            // 写回更新后的结果列表
            redisTemplate.opsForHash().put(key, "results", objectMapper.writeValueAsString(results));

            // 递增完成计数
            Long completed = redisTemplate.opsForHash().increment(key, "completedCount", 1);
            String totalStr = (String) redisTemplate.opsForHash().get(key, "totalCount");
            int total = totalStr != null ? Integer.parseInt(totalStr) : 0;

            log.info("[AgentStateManager] 子任务结果写回: correlationId={}, completed={}/{}, taskType={}",
                    correlationId, completed, total, result.getTaskType());

            if (completed != null && completed >= total && total > 0) {
                redisTemplate.opsForHash().put(key, "status", "READY");
                log.info("[AgentStateManager] 所有子任务完成，状态→READY: correlationId={}", correlationId);
                return true;
            }

            redisTemplate.opsForHash().put(key, "status", "PARTIAL");
            return false;

        } catch (Exception e) {
            log.error("[AgentStateManager] 写回结果失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 读取所有子任务结果（供汇总 Agent 使用）
     */
    public List<AgentTaskResult> getResults(String correlationId) {
        String key = KEY_PREFIX + correlationId;
        String json = (String) redisTemplate.opsForHash().get(key, "results");
        return parseResults(json);
    }

    /**
     * 获取所有检索结果（合并所有 RETRIEVAL 类型子任务的 searchResults）
     */
    public List<SearchResult> getMergedSearchResults(String correlationId) {
        List<AgentTaskResult> results = getResults(correlationId);
        List<SearchResult> merged = new ArrayList<>();
        results.stream()
                .filter(r -> r.isSuccess() && r.getSearchResults() != null)
                .forEach(r -> merged.addAll(r.getSearchResults()));
        return merged;
    }

    /**
     * 标记任务完成
     */
    public void markDone(String correlationId) {
        String key = KEY_PREFIX + correlationId;
        redisTemplate.opsForHash().put(key, "status", "DONE");
    }

    @SuppressWarnings("unchecked")
    private List<AgentTaskResult> parseResults(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, AgentTaskResult.class));
        } catch (Exception e) {
            log.warn("[AgentStateManager] 解析结果列表失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
