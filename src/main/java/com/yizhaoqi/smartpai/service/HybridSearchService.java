package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.yizhaoqi.smartpai.dto.EsDocument;
import com.yizhaoqi.smartpai.dto.SearchResult;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Hybrid retrieval service combining vector recall and BM25 ranking.
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    private final ElasticsearchClient esClient;
    private final LangChain4jEmbeddingService embeddingService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final OrgTagCacheService orgTagCacheService;
    private final FileUploadRepository fileUploadRepository;

    public HybridSearchService(ElasticsearchClient esClient,
                               LangChain4jEmbeddingService embeddingService,
                               UserService userService,
                               UserRepository userRepository,
                               OrgTagCacheService orgTagCacheService,
                               FileUploadRepository fileUploadRepository) {
        this.esClient = esClient;
        this.embeddingService = embeddingService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.orgTagCacheService = orgTagCacheService;
        this.fileUploadRepository = fileUploadRepository;
    }

    /**
     * Unified search entrypoint.
     */
    public List<SearchResult> search(String query, String userId, int topK) {
        if (userId == null || userId.isBlank()) {
            return search(query, topK);
        }
        return searchWithPermission(query, userId, topK);
    }

    /**
     * Permission-aware hybrid search.
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        logger.info("Start permission-aware search, query='{}', userId='{}', topK={}", query, userId, topK);

        try {
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            List<String> userSearchIds = getUserSearchIds(userId);

            List<Float> queryVector = embedToVectorList(query);
            if (queryVector == null) {
                logger.warn("Embedding generation failed, fallback to text-only permission search.");
                return textOnlySearchWithPermission(query, userSearchIds, userEffectiveTags, topK);
            }

            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index("knowledge_base");
                int recallK = topK * 30;

                s.knn(kn -> kn
                        .field("vector")
                        .queryVector(queryVector)
                        .k(recallK)
                        .numCandidates(recallK));

                s.query(q -> q.bool(b -> b
                        .filter(f -> f.bool(bf -> applyPermissionFilter(bf, userSearchIds, userEffectiveTags)))));

                s.rescore(r -> r
                        .windowSize(recallK)
                        .query(rq -> rq
                                .queryWeight(0.2d)
                                .rescoreQueryWeight(1.0d)
                                .query(rqq -> rqq.match(m -> m
                                        .field("textContent")
                                        .query(query)
                                        .operator(Operator.And)))));

                s.size(topK);
                return s;
            }, EsDocument.class);

            List<SearchResult> results = toSearchResults(response);
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("Permission-aware hybrid search failed.", e);
            try {
                return textOnlySearchWithPermission(query, getUserSearchIds(userId), getUserEffectiveOrgTags(userId), topK);
            } catch (Exception fallbackError) {
                logger.error("Fallback text-only permission search also failed.", fallbackError);
                return Collections.emptyList();
            }
        }
    }

    private List<SearchResult> textOnlySearchWithPermission(String query,
                                                            List<String> userSearchIds,
                                                            List<String> userEffectiveTags,
                                                            int topK) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                            .index("knowledge_base")
                            .query(q -> q.bool(b -> b
                                    .must(m -> m.match(ma -> ma.field("textContent").query(query)))
                                    .filter(f -> f.bool(bf -> applyPermissionFilter(bf, userSearchIds, userEffectiveTags)))))
                            .minScore(0.3d)
                            .size(topK),
                    EsDocument.class
            );

            List<SearchResult> results = toSearchResults(response);
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("Text-only permission search failed.", e);
            return new ArrayList<>();
        }
    }

    /**
     * Legacy non-permission search path kept for compatibility.
     */
    public List<SearchResult> search(String query, int topK) {
        try {
            List<Float> queryVector = embedToVectorList(query);
            if (queryVector == null) {
                return textOnlySearch(query, topK);
            }

            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index("knowledge_base");
                int recallK = topK * 30;

                s.knn(kn -> kn
                        .field("vector")
                        .queryVector(queryVector)
                        .k(recallK)
                        .numCandidates(recallK));

                s.query(q -> q.match(m -> m.field("textContent").query(query)));

                s.rescore(r -> r
                        .windowSize(recallK)
                        .query(rq -> rq
                                .queryWeight(0.2d)
                                .rescoreQueryWeight(1.0d)
                                .query(rqq -> rqq.match(m -> m
                                        .field("textContent")
                                        .query(query)
                                        .operator(Operator.And)))));

                s.size(topK);
                return s;
            }, EsDocument.class);

            return toSearchResults(response);
        } catch (Exception e) {
            logger.error("Hybrid search failed.", e);
            try {
                return textOnlySearch(query, topK);
            } catch (Exception fallbackError) {
                logger.error("Fallback text-only search failed.", fallbackError);
                throw new RuntimeException("Search completely failed", fallbackError);
            }
        }
    }

    private List<SearchResult> textOnlySearch(String query, int topK) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> s
                        .index("knowledge_base")
                        .query(q -> q.match(m -> m.field("textContent").query(query)))
                        .size(topK),
                EsDocument.class
        );

        return toSearchResults(response);
    }

    private BoolQuery.Builder applyPermissionFilter(BoolQuery.Builder builder,
                                                    List<String> userSearchIds,
                                                    List<String> userEffectiveTags) {
        if (userSearchIds != null) {
            userSearchIds.stream()
                    .filter(id -> id != null && !id.isBlank())
                    .distinct()
                    .forEach(id -> builder.should(s -> s.term(t -> t.field("userId").value(id))));
        }

        builder.should(s1 -> s1.term(t -> t.field("isPublic").value(true)))
                .should(s2 -> s2.term(t -> t.field("public").value(true)))
                .should(s3 -> {
                    if (userEffectiveTags == null || userEffectiveTags.isEmpty()) {
                        return s3.matchNone(mn -> mn);
                    }
                    if (userEffectiveTags.size() == 1) {
                        return s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0)));
                    }
                    return s3.bool(inner -> {
                        userEffectiveTags.forEach(tag -> inner.should(sh -> sh.term(t -> t.field("orgTag").value(tag))));
                        return inner;
                    });
                });
        builder.minimumShouldMatch("1");
        return builder;
    }

    private List<SearchResult> toSearchResults(SearchResponse<EsDocument> response) {
        return response.hits().hits().stream()
                .map(hit -> {
                    EsDocument source = hit.source();
                    if (source == null) {
                        return null;
                    }
                    return new SearchResult(
                            source.getFileMd5(),
                            source.getChunkId(),
                            source.getTextContent(),
                            hit.score(),
                            source.getUserId(),
                            source.getOrgTag(),
                            source.isPublic()
                    );
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Generates query embedding and converts it to List<Float>.
     */
    private List<Float> embedToVectorList(String text) {
        try {
            float[] raw = embeddingService.embed(text);
            if (raw == null || raw.length == 0) {
                logger.warn("Generated embedding is empty.");
                return null;
            }
            List<Float> list = new ArrayList<>(raw.length);
            for (float value : raw) {
                list.add(value);
            }
            return list;
        } catch (Exception e) {
            logger.error("Generate embedding failed.", e);
            return null;
        }
    }

    private List<String> getUserEffectiveOrgTags(String userId) {
        try {
            User user = loadUserByIdOrUsername(userId);
            return orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
        } catch (Exception e) {
            logger.error("Get user effective org tags failed, userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    private List<String> getUserSearchIds(String userId) {
        try {
            User user = loadUserByIdOrUsername(userId);
            return List.of(String.valueOf(user.getId()), user.getUsername());
        } catch (Exception e) {
            logger.error("Get user search ids failed, userId={}", userId, e);
            throw new RuntimeException("Get user search ids failed", e);
        }
    }

    private User loadUserByIdOrUsername(String userId) {
        try {
            Long userIdLong = Long.parseLong(userId);
            return userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
        } catch (NumberFormatException e) {
            return userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
        }
    }

    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }

        try {
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .collect(Collectors.toSet());

            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new ArrayList<>(md5Set));
            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName, (left, right) -> left));

            results.forEach(result -> result.setFileName(md5ToName.get(result.getFileMd5())));
        } catch (Exception e) {
            logger.error("Attach file names failed.", e);
        }
    }
}
