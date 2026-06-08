package com.yizhaoqi.smartpai.test;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.yizhaoqi.smartpai.dto.EsDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 诊断工具
 * 用于检查知识库索引状态和数据
 */
@Component
public class EsDiagnosticTool implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(EsDiagnosticTool.class);

    @Autowired
    private ElasticsearchClient esClient;

    @Override
    public void run(String... args) throws Exception {
        // 只在指定参数时运行诊断
        if (args.length > 0 && "diagnose-es".equals(args[0])) {
            diagnoseElasticsearch();
        }
    }

    /**
     * 诊断 Elasticsearch 状态
     */
    public void diagnoseElasticsearch() {
        logger.info("\n\n");
        logger.info("========================================");
        logger.info("  Elasticsearch 诊断工具启动");
        logger.info("========================================\n");

        try {
            // 1. 检查索引是否存在
            logger.info("1️⃣ 检查索引 'knowledge_base' 是否存在...");
            boolean indexExists = esClient.indices().exists(e -> e.index("knowledge_base")).value();
            if (indexExists) {
                logger.info("   ✅ 索引 'knowledge_base' 存在");
            } else {
                logger.error("   ❌ 索引 'knowledge_base' 不存在！");
                logger.error("   请先上传文档以创建索引");
                return;
            }

            // 2. 获取索引统计信息
            logger.info("\n2️⃣ 获取索引统计信息...");
            var stats = esClient.indices().stats(s -> s.index("knowledge_base"));
            long docCount = stats.indices().get("knowledge_base").primaries().docs().count();
            logger.info("   📊 文档总数: {}", docCount);
            
            if (docCount == 0) {
                logger.error("   ⚠️ 索引中没有文档！请先上传并处理文档");
                return;
            }

            // 3. 查看部分文档样本
            logger.info("\n3️⃣ 查看文档样本（前5条）...");
            SearchResponse<EsDocument> sampleResponse = esClient.search(s -> s
                    .index("knowledge_base")
                    .size(5),
                    EsDocument.class
            );

            int count = 1;
            for (var hit : sampleResponse.hits().hits()) {
                EsDocument doc = hit.source();
                if (doc != null) {
                    logger.info("   [{}] ID: {}", count, doc.getId());
                    logger.info("       FileMD5: {}", doc.getFileMd5());
                    logger.info("       ChunkID: {}", doc.getChunkId());
                    logger.info("       UserID: {}", doc.getUserId());
                    logger.info("       OrgTag: {}", doc.getOrgTag());
                    logger.info("       IsPublic: {}", doc.isPublic());
                    logger.info("       Content Preview: {}...", 
                        doc.getTextContent().substring(0, Math.min(100, doc.getTextContent().length())));
                    logger.info("");
                    count++;
                }
            }

            // 4. 检查权限字段分布
            logger.info("\n4️⃣ 检查权限字段分布...");
            
            // 统计公开文档数量
            SearchResponse<EsDocument> publicDocs = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q.term(t -> t.field("public").value(true)))
                    .size(0),
                    EsDocument.class
            );
            logger.info("   📄 公开文档数量: {}", publicDocs.hits().total().value());

            // 统计所有唯一的 userId
            logger.info("\n5️⃣ 统计用户文档分布...");
            // 这里简化处理，实际可以使用 aggregation
            
            logger.info("\n========================================");
            logger.info("  诊断完成");
            logger.info("========================================\n");

        } catch (Exception e) {
            logger.error("❌ 诊断过程中发生错误", e);
        }
    }
}
