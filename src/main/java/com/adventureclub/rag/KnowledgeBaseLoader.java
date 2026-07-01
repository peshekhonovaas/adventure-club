package com.adventureclub.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Runs once on application startup.
 * What it does:
 *   1. Reads knowledge-base.json from the classpath
 *   2. Creates a Spring AI Document for each entry
 *   3. Calls vectorStore.add() — Spring AI calls the ONNX model
 *      to convert each document's text to a vector[384]
 *   4. Spring AI writes (content + vector) to the vector_store table
 * On subsequent restarts it detects that rows already exist and skips.
 * To force a full re-index (e.g. after editing knowledge-base.json):
 *   DELETE FROM vector_store;
 *   then restart the app.
 */
@Component
public class KnowledgeBaseLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseLoader.class);

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Value("classpath:knowledge-base.json")
    private Resource knowledgeBaseResource;

    public KnowledgeBaseLoader(VectorStore vectorStore,
                               JdbcTemplate jdbc,
                               ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Check if the knowledge base is already indexed
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vector_store", Integer.class);

        if (count != null && count > 0) {
            log.info("Knowledge base already indexed ({} entries) — skipping", count);
            return;
        }

        log.info("Indexing knowledge base...");

        // Step 1: parse knowledge-base.json
        List<KnowledgeEntry> entries = objectMapper.readValue(
                knowledgeBaseResource.getInputStream(),
                new TypeReference<>() {}
        );

        // Step 2: convert each entry to a Spring AI Document
        //
        // Document holds:
        //   - text  → what gets embedded and stored in the content column
        //   - metadata → stored as JSONB, useful for filtering later
        //
        // We embed toEmbeddingText() (content + activity combined) so that
        // both the educational fact AND the hands-on task are searchable.
        List<Document> documents = entries.stream()
                .map(entry -> new Document(
                        entry.toEmbeddingText(),
                        Map.of(
                                "id",    entry.id(),
                                "topic", entry.topic()
                        )
                ))
                .toList();

        // Step 3: add to vector store
        //
        // Spring AI does two things here automatically:
        //   a) calls the ONNX embedding model for each document → vector[384]
        //   b) inserts (id, content, metadata, embedding) into vector_store table
        //
        // With 20 documents this takes about 1-2 seconds on first run.
        vectorStore.add(documents);

        log.info("Knowledge base indexed: {} entries written to vector_store", documents.size());
    }
}
