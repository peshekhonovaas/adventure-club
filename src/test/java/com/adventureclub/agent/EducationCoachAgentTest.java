package com.adventureclub.agent;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for {@link EducationCoachAgent}.
 * The VectorStore (which would otherwise call the ONNX model + Postgres) is
 * replaced by a hand-rolled fake, so this test is fast, deterministic and needs
 * no external services. A hand-written fake is used instead of Mockito because
 * Mockito's inline mock maker cannot instrument interfaces on newer JDKs.
 * It verifies the agent's own logic: query construction, search parameters,
 * result joining, and graceful degradation when nothing relevant is found.
 */
class EducationCoachAgentTest {

    /**
     * Minimal VectorStore stand-in: records the SearchRequest it was handed and
     * returns a pre-programmed result list. Only similaritySearch matters here.
     */
    static class FakeVectorStore implements VectorStore {
        SearchRequest lastRequest;
        List<Document> resultToReturn;

        FakeVectorStore(List<Document> resultToReturn) {
            this.resultToReturn = resultToReturn;
        }

        @Override
        public List<Document> similaritySearch(@NonNull SearchRequest request) {
            this.lastRequest = request;
            return resultToReturn;
        }

        @Override
        public void add(@NonNull List<Document> documents) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public void delete(@NonNull List<String> idList) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public void delete(Filter.@NonNull Expression filterExpression) {
            throw new UnsupportedOperationException("not needed for this test");
        }
    }

    @Test
    void findEnrichment_joinsMultipleResultsWithSeparator() {
        FakeVectorStore vectorStore = new FakeVectorStore(List.of(
                Document.builder().text("Dragons are mythical reptiles.").build(),
                Document.builder().text("Basalt is a common volcanic rock.").build()
        ));
        EducationCoachAgent educationCoach = new EducationCoachAgent(vectorStore);

        String enrichment = educationCoach.findEnrichment("dragons, rocks", "I found a black stone!");

        assertThat(enrichment)
                .isEqualTo("Dragons are mythical reptiles.\n\n---\n\nBasalt is a common volcanic rock.");
    }

    @Test
    void findEnrichment_singleResult_hasNoSeparator() {
        FakeVectorStore vectorStore = new FakeVectorStore(
                List.of(Document.builder().text("Only one fact.").build()));
        EducationCoachAgent educationCoach = new EducationCoachAgent(vectorStore);

        String enrichment = educationCoach.findEnrichment("space", "tell me about stars");

        assertThat(enrichment).isEqualTo("Only one fact.");
    }

    @Test
    void findEnrichment_returnsEmptyString_whenNoResults() {
        FakeVectorStore vectorStore = new FakeVectorStore(List.of());
        EducationCoachAgent educationCoach = new EducationCoachAgent(vectorStore);

        String enrichment = educationCoach.findEnrichment("dragons", "hello");

        assertThat(enrichment).isEmpty();
    }

    @Test
    void findEnrichment_returnsEmptyString_whenSearchReturnsNull() {
        // similaritySearch is nullable in the Spring AI contract; the agent
        // guards against it so RAG failure never breaks the story.
        FakeVectorStore vectorStore = new FakeVectorStore(null);
        EducationCoachAgent educationCoach = new EducationCoachAgent(vectorStore);

        String enrichment = educationCoach.findEnrichment("dragons", "hello");

        assertThat(enrichment).isEmpty();
    }

    @Test
    void findEnrichment_buildsQueryFromInterestsAndMessage() {
        FakeVectorStore vectorStore = new FakeVectorStore(List.of());
        EducationCoachAgent educationCoach = new EducationCoachAgent(vectorStore);

        educationCoach.findEnrichment("dinosaurs", "I dug up a big bone");

        assertThat(vectorStore.lastRequest.getQuery()).isEqualTo("dinosaurs. I dug up a big bone");
    }

    @Test
    void findEnrichment_usesConfiguredTopKAndSimilarityThreshold() {
        FakeVectorStore vectorStore = new FakeVectorStore(List.of());
        EducationCoachAgent educationCoach = new EducationCoachAgent(vectorStore);

        educationCoach.findEnrichment("volcanoes", "why is lava hot?");

        assertThat(vectorStore.lastRequest.getTopK()).isEqualTo(2);
        assertThat(vectorStore.lastRequest.getSimilarityThreshold()).isEqualTo(0.5);
    }
}
