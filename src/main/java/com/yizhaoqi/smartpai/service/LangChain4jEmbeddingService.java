package com.yizhaoqi.smartpai.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j 向量化服务
 * 封装 LangChain4j 的 Embedding 能力
 */
@Service
public class LangChain4jEmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jEmbeddingService.class);

    private final EmbeddingModel embeddingModel;

    public LangChain4jEmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 单文本向量化
     */
    public float[] embed(String text) {
        logger.debug("[LangChain4j] 单文本向量化: length={}", text.length());
        
        Response<Embedding> response = embeddingModel.embed(text);
        return response.content().vector();
    }

    /**
     * 批量文本向量化
     */
    public List<float[]> embedBatch(List<String> texts) {
        logger.info("[LangChain4j] 批量向量化: count={}", texts.size());
        
        List<TextSegment> segments = new ArrayList<>();
        for (String text : texts) {
            segments.add(TextSegment.from(text));
        }
        
        Response<List<Embedding>> response = embeddingModel.embedAll(segments);
        
        List<float[]> vectors = new ArrayList<>();
        for (Embedding embedding : response.content()) {
            vectors.add(embedding.vector());
        }
        
        logger.info("[LangChain4j] 向量化完成: count={}", vectors.size());
        return vectors;
    }

    /**
     * 获取向量维度
     */
    public int dimension() {
        return embeddingModel.dimension();
    }
}
