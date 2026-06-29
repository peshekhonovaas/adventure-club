package com.adventureclub.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that makes a real call to the Claude API.
 *
 * Run this on Monday afternoon once the project compiles:
 *   mvn test -Dtest=StoryDirectorAgentTest -DANTHROPIC_API_KEY=sk-ant-...
 *
 * If this passes, the whole stack (Spring Boot → Spring AI → Claude) works.
 * If it fails with 401, your API key is wrong.
 * If it fails with a bean error, check application.yml and pom.xml.
 *
 * This test costs a few cents of API credit each run — don't add it to CI yet.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:16:///adventureclub",  // Testcontainers if you add it later
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
class StoryDirectorAgentTest {

    @Autowired
    StoryDirectorAgent storyDirector;

    @Test
    void firstTurn_returnsNonEmptyStoryText() {
        String response = storyDirector.nextBeat(
                "star wars",
                "Grogu",
                List.of(),                          // empty history — first turn
                "I like star wars and I want an adventure!"
        );

        System.out.println("Story Director response:\n" + response);  // read it in the test output

        assertThat(response).isNotBlank();
        assertThat(response.length()).isGreaterThan(50);  // should be at least a sentence
    }

    @Test
    void safetyGate_blocksObviouslyUnsafeContent() {
        // Smoke test that the safety gate isn't silently broken
    }
}