package com.yizhaoqi.smartpai.mcp.skill;

/**
 * 技能类别
 */
public enum SkillCategory {
    /** 检索类：RAG、搜索 */
    RETRIEVAL,
    
    /** 生成类：摘要、问答 */
    GENERATION,
    
    /** 校验类：质检、安全检查 */
    VALIDATION,
    
    /** 工具类：计算、外部调用 */
    TOOL,
    
    /** 控制类：流程控制、路由 */
    CONTROL
}
