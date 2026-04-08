package com.yizhaoqi.smartpai.mcp.context;

/**
 * MCP 状态枚举
 */
public enum McpState {
    /** 初始状态 */
    INIT,
    
    /** 记忆检索中 */
    MEMORY_RETRIEVAL,
    
    /** 意图识别中 */
    INTENT_RECOGNITION,
    
    /** 技能选择中 */
    SKILL_SELECTION,
    
    /** RAG 检索中 */
    RAG_RETRIEVAL,
    
    /** 联网搜索中 */
    WEB_SEARCH,
    
    /** 生成回复中 */
    GENERATION,
    
    /** 质量检查中 */
    QUALITY_CHECK,
    
    /** 重试中 */
    RETRY,
    
    /** 完成 */
    COMPLETED,
    
    /** 错误 */
    ERROR
}
