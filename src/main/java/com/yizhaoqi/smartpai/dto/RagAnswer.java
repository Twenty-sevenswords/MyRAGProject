package com.yizhaoqi.smartpai.dto;

import java.util.List;

/**
 * RAG answer DTO for LangGraph pipeline.
 */
public class RagAnswer {
    private final String reply;
    private final List<SearchResult> searchResults;
    private final boolean usedLlm;

    public RagAnswer(String reply, List<SearchResult> searchResults, boolean usedLlm) {
        this.reply = reply;
        this.searchResults = searchResults;
        this.usedLlm = usedLlm;
    }

    public String getReply() {
        return reply;
    }

    public List<SearchResult> getSearchResults() {
        return searchResults;
    }

    public boolean isUsedLlm() {
        return usedLlm;
    }
}
