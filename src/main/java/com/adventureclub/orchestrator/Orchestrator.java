package com.adventureclub.orchestrator;

import com.adventureclub.agent.EducationCoachAgent;
import com.adventureclub.agent.SafetyGate;
import com.adventureclub.agent.StoryDirectorAgent;
import com.adventureclub.domain.Message;
import com.adventureclub.domain.Session;
import com.adventureclub.domain.TurnRequest;
import com.adventureclub.domain.TurnResponse;
import com.adventureclub.repository.MessageRepository;
import com.adventureclub.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Orchestrator — the brain of the agent pipeline.
 * This is DETERMINISTIC CODE, not an LLM. It decides the order of operations:
 *   1. Resolve or create session
 *   2. Run input safety gate
 *   3. If safe → load history → call Story Director
 *   4. Persist both messages
 *   5. Return response
 * Design decision: the orchestrator is plain Java, not an LLM agent.
 * Reason: the pipeline is sequential and stateful. An LLM-as-router adds
 * unpredictability precisely where you want none — especially around step 2.
 * In phase 2 this class will also call the Education Coach before step 3.
 * In phase 3 it will optionally call the Creativity Coach when an image is attached.
 */
@Service
public class Orchestrator {

    private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);

    private final SafetyGate safetyGate;
    private final StoryDirectorAgent storyDirector;
    private final EducationCoachAgent educationCoach;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;

    public Orchestrator(SafetyGate safetyGate,
                        EducationCoachAgent educationCoach,
                        StoryDirectorAgent storyDirector,
                        SessionRepository sessionRepository,
                        MessageRepository messageRepository) {
        this.safetyGate = safetyGate;
        this.storyDirector = storyDirector;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.educationCoach = educationCoach;
    }

    @Transactional
    public TurnResponse processTurn(TurnRequest request) {
        // Step 1: resolve or create session
        Session session = resolveSession(request);
        log.debug("Processing turn for session={}, interests='{}'",
                session.getId(), session.getInterests());

        // Step 2: input safety gate — runs BEFORE the story agent sees anything
        if (!safetyGate.isSafe(request.childMessage())) {
            // Do not persist blocked messages — no data about what was blocked
            return new TurnResponse(session.getId(), null, true);
        }

        // Step 3 — Education Coach: find relevant knowledge base entries  ← NEW
        //
        // This is a vector similarity search — takes ~5ms locally.
        // Returns empty string if nothing relevant found.
        // The Story Director gracefully handles both cases.
        String enrichment = educationCoach.findEnrichment(
                session.getInterests(),
                request.childMessage()
        );

        // Step 4: load full conversation history for this session
        List<Message> history = messageRepository
                .findBySessionIdOrderByCreatedAtAsc(session.getId());

        // Step 5: Story Director, now with optional enrichment
        String storyText = storyDirector.nextBeat(
                session.getInterests(),
                request.agentName(),
                history,
                request.childMessage(),
                enrichment
        );

        // Step 6: persist both the child's message and the assistant response
        // Persist child's message first so history ordering is correct on next turn
        messageRepository.save(new Message(session.getId(), Message.Role.USER, request.childMessage()));
        messageRepository.save(new Message(session.getId(), Message.Role.ASSISTANT, storyText));

        return new TurnResponse(session.getId(), storyText, false);
    }

    private Session resolveSession(TurnRequest request) {
        if (request.sessionId() != null) {
            return sessionRepository.findById(request.sessionId())
                    .orElseGet(() -> createSession(request));
        }
        return createSession(request);
    }

    private Session createSession(TurnRequest request) {
        // childName is "explorer" for now — add proper onboarding in phase 2
        Session session = new Session("explorer", request.interests(), request.agentName());
        Session saved = sessionRepository.save(session);
        log.info("Created new session={} with interests='{}' and agent name='{}'", saved.getId(),
                saved.getInterests(), saved.getAgentName());
        return saved;
    }
}