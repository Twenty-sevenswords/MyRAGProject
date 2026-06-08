package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.config.properties.QaMemoryProperties;
import com.yizhaoqi.smartpai.dto.AgentIntent;
import com.yizhaoqi.smartpai.dto.AgentResult;
import com.yizhaoqi.smartpai.dto.QaMemoryEntry;
import com.yizhaoqi.smartpai.repository.RedisRepository;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

/**
 * 问答记忆服务
 * 负责历史问答的缓存、相似问题匹配和记忆管理
 */
@Service
public class QaMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(QaMemoryService.class);

    @Autowired
    private QaMemoryProperties properties;

    @Autowired
    private RedisRepository redisRepository;

    /**
     * 尝试从历史记忆中获取答案
     * @param question 用户问题
     * @param userId 用户ID
     * @param intent 意图分析结果（可选，用于关键词匹配）
     * @return 命中的记忆条目，未命中返回空
     */
    public Optional<QaMemoryEntry> findSimilarAnswer(String question, String userId, AgentIntent intent) {
        if (!properties.isEnabled()) {
            logger.debug("[QaMemory] 问答记忆功能已禁用");
            return Optional.empty();
        }

        logger.info("[QaMemory] 开始查找相似问题记忆, userId={}, question={}", userId, question);

        // 1. 先尝试精确匹配（哈希匹配）
        String questionHash = hashQuestion(question);
        QaMemoryEntry exactMatch = redisRepository.findByQuestionHash(userId, questionHash, properties.getKeyPrefix());
        
        if (exactMatch != null) {
            logger.info("[QaMemory] 精确匹配命中, memoryId={}, accessCount={}", 
                    exactMatch.getId(), exactMatch.getAccessCount());
            exactMatch.recordAccess();
            redisRepository.updateQaMemory(exactMatch, properties.getKeyPrefix(), properties.getExpireDays());
            return Optional.of(exactMatch);
        }

        // 2. 尝试相似度匹配
        List<QaMemoryEntry> memories = redisRepository.getUserQaMemories(userId, properties.getKeyPrefix());
        logger.debug("[QaMemory] 用户历史记忆数量: {}", memories.size());

        if (memories.isEmpty()) {
            return Optional.empty();
        }

        // 3. 计算相似度并找出最佳匹配
        Optional<QaMemoryEntry> bestMatch = memories.stream()
                .filter(entry -> !entry.isExpired())
                .map(entry -> new MatchResult(entry, calculateSimilarity(question, entry, intent)))
                .filter(result -> result.similarity >= properties.getSimilarityThreshold())
                .max((a, b) -> Double.compare(a.similarity, b.similarity))
                .map(result -> {
                    logger.info("[QaMemory] 相似问题命中, similarity={}, originalQuestion={}, matchedQuestion={}",
                            String.format("%.2f", result.similarity), 
                            question, result.entry.getQuestion());
                    result.entry.recordAccess();
                    redisRepository.updateQaMemory(result.entry, properties.getKeyPrefix(), properties.getExpireDays());
                    return result.entry;
                });

        return bestMatch;
    }

    /**
     * 保存问答记忆
     */
    public void saveMemory(String question, String userId, AgentIntent intent, AgentResult result) {
        if (!properties.isEnabled()) {
            return;
        }

        // 检查记忆数量限制
        long count = redisRepository.getQaMemoryCount(userId, properties.getKeyPrefix());
        if (count >= properties.getMaxMemoryPerUser()) {
            logger.info("[QaMemory] 用户记忆已达上限 {}, 执行清理", properties.getMaxMemoryPerUser());
            redisRepository.cleanExpiredQaMemories(userId, properties.getKeyPrefix());
            
            // 如果清理后仍然超限，不再保存
            count = redisRepository.getQaMemoryCount(userId, properties.getKeyPrefix());
            if (count >= properties.getMaxMemoryPerUser()) {
                logger.warn("[QaMemory] 记忆数量已达上限，跳过保存");
                return;
            }
        }

        String questionHash = hashQuestion(question);
        QaMemoryEntry entry = QaMemoryEntry.create(
                userId,
                question,
                questionHash,
                intent != null ? intent.getKeywords() : null,
                result.getReply(),
                result.getSources(),
                result.isUsedLLM(),
                properties.getExpireDays()
        );

        redisRepository.saveQaMemory(entry, properties.getKeyPrefix(), properties.getExpireDays());
        logger.info("[QaMemory] 问答记忆已保存, id={}, userId={}, expireDays={}",
                entry.getId(), userId, properties.getExpireDays());
    }

    /**
     * 保存问答记忆（简化版本，用于普通问答模式）
     * @param question 用户问题
     * @param userId 用户ID
     * @param answer 回答内容
     * @param sources 来源列表（可为null）
     * @param usedLLM 是否使用了LLM
     */
    public void saveSimpleMemory(String question, String userId, String answer, List<?> sources, boolean usedLLM) {
        if (!properties.isEnabled()) {
            return;
        }

        // 检查记忆数量限制
        long count = redisRepository.getQaMemoryCount(userId, properties.getKeyPrefix());
        if (count >= properties.getMaxMemoryPerUser()) {
            logger.info("[QaMemory] 用户记忆已达上限 {}, 执行清理", properties.getMaxMemoryPerUser());
            redisRepository.cleanExpiredQaMemories(userId, properties.getKeyPrefix());
            
            count = redisRepository.getQaMemoryCount(userId, properties.getKeyPrefix());
            if (count >= properties.getMaxMemoryPerUser()) {
                logger.warn("[QaMemory] 记忆数量已达上限，跳过保存");
                return;
            }
        }

        String questionHash = hashQuestion(question);
        QaMemoryEntry entry = QaMemoryEntry.create(
                userId,
                question,
                questionHash,
                null,  // 普通问答模式没有关键词提取
                answer,
                sources,
                usedLLM,
                properties.getExpireDays()
        );

        redisRepository.saveQaMemory(entry, properties.getKeyPrefix(), properties.getExpireDays());
        logger.info("[QaMemory] 简化问答记忆已保存, id={}, userId={}, expireDays={}",
                entry.getId(), userId, properties.getExpireDays());
    }

    /**
     * 计算问题相似度
     */
    private double calculateSimilarity(String question, QaMemoryEntry entry, AgentIntent currentIntent) {
        // 1. 文本相似度（Levenshtein距离）
        double textSimilarity = calculateTextSimilarity(question, entry.getQuestion());

        // 2. 关键词相似度
        double keywordSimilarity = calculateKeywordSimilarity(currentIntent, entry);

        // 3. 综合相似度
        // 当有关键词信息时：文本60% + 关键词40%
        // 当没有关键词信息时：完全依赖文本相似度
        double totalSimilarity;
        boolean hasKeywordInfo = currentIntent != null && currentIntent.getKeywords() != null &&
                                  !currentIntent.getKeywords().isEmpty() &&
                                  entry.getKeywords() != null && !entry.getKeywords().isEmpty();
        
        if (hasKeywordInfo) {
            totalSimilarity = textSimilarity * 0.6 + keywordSimilarity * 0.4;
        } else {
            // 没有关键词信息时，完全依赖文本相似度
            totalSimilarity = textSimilarity;
        }

        logger.debug("[QaMemory] 相似度计算: textSim={}, keywordSim={}, hasKeywordInfo={}, total={}",
                String.format("%.2f", textSimilarity),
                String.format("%.2f", keywordSimilarity),
                hasKeywordInfo,
                String.format("%.2f", totalSimilarity));

        return totalSimilarity;
    }

    /**
     * 计算文本相似度（基于Levenshtein距离）
     */
    private double calculateTextSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null) {
            return 0.0;
        }

        // 标准化处理
        String s1 = text1.trim().toLowerCase();
        String s2 = text2.trim().toLowerCase();

        if (s1.equals(s2)) {
            return 1.0;
        }

        // 使用Levenshtein距离计算相似度
        LevenshteinDistance distance = new LevenshteinDistance();
        int maxLen = Math.max(s1.length(), s2.length());
        if (maxLen == 0) {
            return 1.0;
        }

        int editDistance = distance.apply(s1, s2);
        return 1.0 - (double) editDistance / maxLen;
    }

    /**
     * 计算关键词相似度
     */
    private double calculateKeywordSimilarity(AgentIntent currentIntent, QaMemoryEntry entry) {
        if (currentIntent == null || currentIntent.getKeywords() == null || 
            entry.getKeywords() == null || entry.getKeywords().isEmpty()) {
            return 0.0;
        }

        List<String> currentKeywords = currentIntent.getKeywords();
        List<String> memoryKeywords = entry.getKeywords();

        // 计算关键词交集
        long matchCount = currentKeywords.stream()
                .filter(keyword -> memoryKeywords.stream()
                        .anyMatch(mk -> mk.toLowerCase().contains(keyword.toLowerCase()) ||
                                       keyword.toLowerCase().contains(mk.toLowerCase())))
                .count();

        // Jaccard相似度
        int unionSize = currentKeywords.size() + memoryKeywords.size() - (int) matchCount;
        if (unionSize == 0) {
            return 0.0;
        }

        return (double) matchCount / unionSize;
    }

    /**
     * 计算问题的哈希值
     */
    private String hashQuestion(String question) {
        if (question == null) {
            return "";
        }
        
        // 标准化问题文本（去除标点、多余空格等）
        String normalized = question.trim()
                .toLowerCase()
                .replaceAll("[\\p{Punct}\\s]+", " ")
                .trim();

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            logger.error("[QaMemory] 哈希计算失败", e);
            return String.valueOf(normalized.hashCode());
        }
    }

    /**
     * 清理用户过期的记忆
     */
    public void cleanUserExpiredMemories(String userId) {
        redisRepository.cleanExpiredQaMemories(userId, properties.getKeyPrefix());
        logger.info("[QaMemory] 已清理用户 {} 的过期记忆", userId);
    }

    /**
     * 按记忆ID删除问答记忆
     */
    public void deleteMemoryById(String userId, String memoryId) {
        if (userId == null || userId.isBlank() || memoryId == null || memoryId.isBlank()) {
            return;
        }
        redisRepository.deleteQaMemory(userId, memoryId, properties.getKeyPrefix());
        logger.info("[QaMemory] 已删除问答记忆, userId={}, memoryId={}", userId, memoryId);
    }

    /**
     * 匹配结果内部类
     */
    private static class MatchResult {
        final QaMemoryEntry entry;
        final double similarity;

        MatchResult(QaMemoryEntry entry, double similarity) {
            this.entry = entry;
            this.similarity = similarity;
        }
    }
}
