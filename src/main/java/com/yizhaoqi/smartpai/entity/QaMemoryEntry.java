package com.yizhaoqi.smartpai.entity;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 问答记忆实体类
 * 用于缓存历史问答结果，支持相似问题命中
 */
@Data
public class QaMemoryEntry {

    /** 唯一标识 */
    private String id;

    /** 用户ID */
    private String userId;

    /** 原始问题文本 */
    private String question;

    /** 问题文本的哈希值，用于快速匹配 */
    private String questionHash;

    /** 问题的关键词列表 */
    private List<String> keywords;

    /** 回答内容 */
    private String answer;

    /** 来源列表 */
    private List<?> sources;

    /** 是否使用了LLM生成 */
    private boolean usedLLM;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 过期时间 */
    private LocalDateTime expireTime;

    /** 访问次数 */
    private int accessCount;

    /** 最后访问时间 */
    private LocalDateTime lastAccessTime;

    /**
     * 创建新的记忆条目
     */
    public static QaMemoryEntry create(String userId, String question, String questionHash,
                                       List<String> keywords, String answer, List<?> sources,
                                       boolean usedLLM, int expireDays) {
        QaMemoryEntry entry = new QaMemoryEntry();
        entry.setId(java.util.UUID.randomUUID().toString());
        entry.setUserId(userId);
        entry.setQuestion(question);
        entry.setQuestionHash(questionHash);
        entry.setKeywords(keywords);
        entry.setAnswer(answer);
        entry.setSources(sources);
        entry.setUsedLLM(usedLLM);
        entry.setCreateTime(LocalDateTime.now());
        entry.setExpireTime(LocalDateTime.now().plusDays(expireDays));
        entry.setAccessCount(0);
        entry.setLastAccessTime(LocalDateTime.now());
        return entry;
    }

    /**
     * 记录访问
     */
    public void recordAccess() {
        this.accessCount++;
        this.lastAccessTime = LocalDateTime.now();
    }

    /**
     * 检查是否过期
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expireTime);
    }

}
