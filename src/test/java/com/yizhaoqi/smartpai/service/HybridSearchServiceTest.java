package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.yizhaoqi.smartpai.dto.SearchResult;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;

class HybridSearchServiceTest {

    @Test
    void shouldUsePermissionPathWhenUserIdPresent() {
        HybridSearchService service = Mockito.spy(new HybridSearchService(
                Mockito.mock(ElasticsearchClient.class),
                Mockito.mock(LangChain4jEmbeddingService.class),
                Mockito.mock(UserService.class),
                Mockito.mock(UserRepository.class),
                Mockito.mock(OrgTagCacheService.class),
                Mockito.mock(FileUploadRepository.class)
        ));

        List<SearchResult> expected = List.of(new SearchResult("f1", 1, "text", 0.9));
        doReturn(expected).when(service).searchWithPermission("query", "user1", 3);

        List<SearchResult> actual = service.search("query", "user1", 3);
        assertEquals(1, actual.size());
        assertEquals("f1", actual.get(0).getFileMd5());
    }

    @Test
    void shouldUsePublicPathWhenUserIdMissing() {
        HybridSearchService service = Mockito.spy(new HybridSearchService(
                Mockito.mock(ElasticsearchClient.class),
                Mockito.mock(LangChain4jEmbeddingService.class),
                Mockito.mock(UserService.class),
                Mockito.mock(UserRepository.class),
                Mockito.mock(OrgTagCacheService.class),
                Mockito.mock(FileUploadRepository.class)
        ));

        List<SearchResult> expected = List.of(new SearchResult("f2", 2, "public text", 0.8));
        doReturn(expected).when(service).search("query", 5);

        List<SearchResult> actual = service.search("query", null, 5);
        assertEquals(1, actual.size());
        assertEquals("f2", actual.get(0).getFileMd5());
    }
}
