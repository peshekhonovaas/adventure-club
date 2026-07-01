package com.adventureclub.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Education Coach — the query side of the RAG pipeline.
 * Called by the Orchestrator on every turn, BEFORE the Story Director.
 * What it does:
 *   1. Builds a query string from the child's interests + current message
 *   2. Sends that query to the ONNX model → vector[384]
 *   3. Asks pgvector for the top 2 most similar knowledge base entries
 *   4. Returns their text so the Story Director can weave it into the story
 * If nothing relevant is found (similarity below threshold), it returns
 * an empty string — the Story Director then continues without enrichment.
 * This is the graceful degradation: RAG failure never breaks the story.
 */
@Component
public class EducationCoachAgent {

    private static final Logger log = LoggerFactory.getLogger(EducationCoachAgent.class);

    // Minimum similarity score to include a result.
    // 0.0 = include everything, 1.0 = only exact matches.
    // 0.5 is a good starting point — tune this by watching the logs.
    private static final double SIMILARITY_THRESHOLD = 0.5;

    // How many knowledge base entries to retrieve per turn.
    // 2 is enough — more than 3 makes the Story Director prompt too crowded.
    private static final int TOP_K = 2;

    private final VectorStore vectorStore;

    public EducationCoachAgent(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Finds knowledge base entries relevant to what the child just said.
     *
     * @param interests   the child's interests, e.g. "dragons, pokemon"
     * @param childMessage what the child just typed
     * @return relevant educational text to inject into the Story Director prompt,
     *         or empty string if nothing relevant was found
     */
    public String findEnrichment(String interests, String childMessage) {
        String query = buildQuery(interests, childMessage);

        // Spring AI calls the ONNX model to embed the query,
        // then runs a cosine similarity search against vector_store
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(TOP_K)
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .build()
        );

        if (results == null || results.isEmpty()) {
            log.debug("Education Coach: no relevant entries found for query '{}'", query);
            return "";
        }

        log.debug("Education Coach: found {} relevant entries for query '{}'",
                results.size(), query);

        // Join the retrieved entries into one block of text
        return results.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private String buildQuery(String interests, String childMessage) {
        if (isSubstantive(childMessage)) {
            return interests + ". " + childMessage;
        }
        return interests;
    }

    /**
     * A message is substantive if it is long enough to carry
     * topic-relevant meaning — roughly more than 4 words.
     * Short greetings and acknowledgements ("hi", "ok", "cool!", "yes")
     * are deliberately excluded.
     */
    private boolean isSubstantive(String message) {
        return message != null
                && message.trim().split("\\s+").length > 4;
    }
}