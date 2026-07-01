package com.adventureclub.orchestrator;

import com.adventureclub.agent.EducationCoachAgent;
import com.adventureclub.agent.OutputSafetyGate;
import com.adventureclub.agent.InputSafetyGate;
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
 * Orchestrator — the full agent pipeline with both safety gates.
 * <p>
 * Turn flow:
 * <p>
 *   [child message]
 *        ↓
 *   1. Resolve / create session
 *        ↓
 *   2. INPUT SAFETY GATE  ← blocks unsafe child messages
 *        ↓ (if safe)
 *   3. Education Coach    ← RAG retrieval from knowledge base
 *        ↓
 *   4. Load history
 *        ↓
 *   5. Story Director     ← generates story response
 *        ↓
 *   6. OUTPUT SAFETY GATE ← blocks unsafe story responses
 *        ↓ (if safe)
 *   7. Persist both messages
 *        ↓
 *   [child sees response]
 * <p>
 * IMPORTANT — only persist messages that passed both gates.
 * Blocked messages are never written to the database.
 * Reasons:
 *   - No record of what the child tried to do
 *   - No unsafe content accumulates in conversation history
 *   - Parent dashboard never shows flagged content
 * <p>
 * If the output gate blocks, we persist the fallback response
 * (not the blocked one), so history remains coherent for the
 * Story Director on the next turn.
 */
@Service
public class Orchestrator {

    private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);

    private final InputSafetyGate inputSafetyGate;
    private final StoryDirectorAgent storyDirector;
    private final EducationCoachAgent educationCoach;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final OutputSafetyGate outputGate;

    public Orchestrator(InputSafetyGate inputSafetyGate,
                        OutputSafetyGate outputGate,
                        EducationCoachAgent educationCoach,
                        StoryDirectorAgent storyDirector,
                        SessionRepository sessionRepository,
                        MessageRepository messageRepository) {
        this.inputSafetyGate = inputSafetyGate;
        this.outputGate       = outputGate;
        this.educationCoach   = educationCoach;
        this.storyDirector    = storyDirector;
        this.sessionRepository  = sessionRepository;
        this.messageRepository  = messageRepository;
    }

    @Transactional
    public TurnResponse processTurn(TurnRequest request) {
        // Step 1: resolve or create session
        Session session = resolveSession(request);
        log.debug("Processing turn for session={}, interests='{}'",
                session.getId(), session.getInterests());

        // Step 2: input safety gate — runs BEFORE the story agent sees anything
        if (!inputSafetyGate.isSafe(request.childMessage())) {
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

        // Step 6 — output safety gate
        // Checks the story response before it reaches the child.
        // If blocked, replace with a warm fallback and persist that instead.
        // The child never knows their story was redirected.
        String responseToSend;
        if (outputGate.isSafe(storyText)) {
            responseToSend = storyText;
        } else {
            log.warn("Output gate blocked story response — sending fallback for session={}",
                    session.getId());
            responseToSend = outputGate.fallback();
        }

        // Step 7 — persist both messages
        // We always persist the child's (safe) message.
        // We persist responseToSend — either the real story or the fallback.
        // We never persist blocked content.
        messageRepository.save(new Message(
                session.getId(), Message.Role.USER, request.childMessage()));
        messageRepository.save(new Message(
                session.getId(), Message.Role.ASSISTANT, responseToSend));

        return new TurnResponse(session.getId(), responseToSend, false);
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