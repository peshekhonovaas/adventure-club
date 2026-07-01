package com.adventureclub.rag;

public record KnowledgeEntry(
        String id,
        String topic,
        String content,
        String activity
) {

    public String toEmbeddingText() {
        return topic + ": " + content;
    }
}
