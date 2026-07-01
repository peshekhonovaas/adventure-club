package com.adventureclub.agent;

import com.adventureclub.domain.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Story Director — the Game Master agent.
 * Week 1 goal: get this returning a real story response from Claude,
 * with multi-turn memory working. Everything else is scaffolding.
 * What it does NOT do yet (phase 2+):
 * - Pull educational content from the knowledge base (Education Coach handles that)
 * - Return structured JSON with action buttons (just prose for now)
 * - Review drawings (Creativity Coach handles that)
 */
@Component
public class StoryDirectorAgent {

    private static final Logger log = LoggerFactory.getLogger(StoryDirectorAgent.class);

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are {name}, a Game Master running a personalised adventure for a child.

            The child's interests: {interests}

            Rules you must always follow:
            1. Keep every response to 1 sentences maximum. Children stop reading after that.
            2. Always say greetings at the start of the story game. Examples: "Hi friend", "How are you?", "So glad to see you".
            3. Write in first person — "We see...", "I think...".
            4. Every response must end with ONE specific, concrete safety action for the child to do
               right now. Every time it should be different action at house or outside with parents.
               Examples: "Find something round outside.", "Draw what you think
               the dragon stone looks like.", "Ask a grown-up what their favourite animal is.",
               "Draw the map of the world.", "Draw what you think the star wars ship looks like."
            5. Build on what has already happened in the conversation. Never restart. Never repeat.
                Progress the story to save the child interest.
            6. Connect the story naturally to the child's interests.
            7. Never include violence, frightening content, or anything unsuitable for
               children aged 6–12. If a child steers the story somewhere dark, gently
               redirect it somewhere bright.
            8. Your tone is warm, encouraging, and magical.
            9. Do not change the main character during the story.
            {enrichment_section}
            """;

    // The enrichment block added to the prompt when the Education Coach
    // finds relevant knowledge base content. Deliberately short and clear
    // so it doesn't overwhelm the other instructions.
    private static final String ENRICHMENT_BLOCK = """
            EDUCATIONAL CONTEXT — weave this naturally into the story if relevant.
            Do NOT quote it directly. Use it as inspiration for what {name} says:
            {enrichment}
            """;

    private final ChatClient chatClient;

    public StoryDirectorAgent(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * Generates the next story beat.
     *
     * @param interests    the child's interests, e.g. "dragons, pokemons, star wars"
     * @param history      all previous messages in this session, oldest first
     * @param childMessage what the child just typed
     * @return the next paragraph of the adventure
     */
    public String nextBeat(String interests, String agentName, List<Message> history, String childMessage, String enrichment) {
        String enrichmentSection = !enrichment.isBlank() ? ENRICHMENT_BLOCK
                .replace("{enrichment}", enrichment)
                .replace("{name}", agentName) : "";
        String system = SYSTEM_PROMPT_TEMPLATE
                .replace("{interests}", interests)
                .replace("{name}", agentName)
                .replace("{enrichment_section}", enrichmentSection);

        // Convert our domain Message objects into Spring AI's message types.
        // Spring AI sends these as the conversation history so Claude has full context.
        List<org.springframework.ai.chat.messages.Message> aiMessages = history.stream()
                .map(m -> switch (m.getRole()) {
                    case USER      -> (org.springframework.ai.chat.messages.Message) new UserMessage(m.getContent());
                    case ASSISTANT -> new AssistantMessage(m.getContent());
                })
                .toList();

        String response = chatClient.prompt()
                .system(system)
                .messages(aiMessages)  // history goes here — Claude sees the full conversation
                .user(childMessage)    // the new message goes last
                .call()
                .content();

        log.debug("Story Director — turns={}, enriched={}, response='{}'",
                history.size(), !enrichment.isBlank(), response);

        return response;
    }
}