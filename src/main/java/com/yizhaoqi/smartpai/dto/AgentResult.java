package com.yizhaoqi.smartpai.dto;

import java.util.List;

public class AgentResult {
    private String reply;
    private List<?> sources;
    private boolean usedLLM;
    private int cost;           // LLM调用次数

    // Builder模式
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private AgentResult result = new AgentResult();

        public Builder reply(String reply) {
            result.reply = reply;
            return this;
        }

        public Builder sources(List<?> sources) {
            result.sources = sources;
            return this;
        }

        public Builder usedLLM(boolean used) {
            result.usedLLM = used;
            return this;
        }

        public Builder cost(int cost) {
            result.cost = cost;
            return this;
        }

        public AgentResult build() {
            return result;
        }
    }

    // getter/setter
    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }
    public List<?> getSources() { return sources; }
    public void setSources(List<?> sources) { this.sources = sources; }
    public boolean isUsedLLM() { return usedLLM; }
    public void setUsedLLM(boolean usedLLM) { this.usedLLM = usedLLM; }
    public int getCost() { return cost; }
    public void setCost(int cost) { this.cost = cost; }
}