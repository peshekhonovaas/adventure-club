package com.adventureclub.orchestrator;

import com.adventureclub.agent.EducationCoachAgent;
import com.adventureclub.agent.IllustratorAgent;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 *   4b. Illustrator       ← draws a picture of the PREVIOUS beat + earlier picture(s)
 *        ↓                    (best-effort, BEFORE the new story is generated)
 *   5. Story Director     ← generates story response
 *        ↓
 *   6. OUTPUT SAFETY GATE ← blocks unsafe story responses
 *        ↓ (if safe)
 *   7. Persist both messages
 *        ↓
 *   [child sees response + picture]
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
    private final IllustratorAgent illustrator;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final OutputSafetyGate outputGate;

    public Orchestrator(InputSafetyGate inputSafetyGate,
                        OutputSafetyGate outputGate,
                        EducationCoachAgent educationCoach,
                        StoryDirectorAgent storyDirector,
                        IllustratorAgent illustrator,
                        SessionRepository sessionRepository,
                        MessageRepository messageRepository) {
        this.inputSafetyGate = inputSafetyGate;
        this.outputGate       = outputGate;
        this.educationCoach   = educationCoach;
        this.storyDirector    = storyDirector;
        this.illustrator      = illustrator;
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
            // Do not persist blocked messages — no data about what was blocked.
            // No picture either — nothing safe to illustrate.
            return new TurnResponse(session.getId(), null, null, true);
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

        // Step 4b — Illustrator FIRST: the picture is drawn BEFORE the new story is
        // generated. It grows the adventure's ONE living picture, which is kept
        // server-side on the session (the Illustrator's previous output). The
        // Illustrator only runs when the child just uploaded a drawing this turn — on
        // plain text turns we skip drawing entirely. The base images handed to the
        // Illustrator are exactly these two, in order:
        //   1. the previously generated picture (from the session), when one exists;
        //   2. the fresh upload the child just added.
        // The upload is MERGED INTO the previous picture (it adds to the living picture
        // rather than replacing it). Best-effort — returns null on failure/quota.
        String imageUrl = null;
        List<IllustratorAgent.SourceImage> baseImages = new ArrayList<>();
        if (session.getSceneImageData() != null && !session.getSceneImageData().isBlank()) {
            baseImages.add(new IllustratorAgent.SourceImage(
                        session.getSceneImageData(), session.getSceneImageMediaType()));
        }
        if (request.hasImage()) {
            baseImages.add(new IllustratorAgent.SourceImage(
                    request.imageData(), request.imageMediaType()));
        }
        if (!baseImages.isEmpty()) {
            imageUrl = illustrator.illustrate(session.getInterests(), request.childMessage(), baseImages);
        }

        // Persist ONLY the freshly generated picture as the new living canvas — the
        // uploaded/previous images are not stored. When nothing new was drawn we keep the
        // previously stored picture untouched so the screen never blanks.
        if (imageUrl != null) {
            storeGeneratedScene(session, imageUrl);
        } else {
            imageUrl = asDataUrl(session.getSceneImageData(), session.getSceneImageMediaType());
        }

        // Step 5: Story Director — text only. The story evolves from the conversation
        // and optional educational enrichment; any uploaded picture is deliberately
        // NOT sent here (it goes only to the Illustrator, step 4b), so the two stay
        // decoupled.
        String storyText = storyDirector.nextBeat(
                session.getInterests(),
                request.agentName(),
                history,
                request.childMessage(),
                enrichment
        );

        // Step 6 — output safety gateF
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

        // The picture was already drawn in step 4b (before this new story beat), based on
        // the PREVIOUS beat and the earlier picture(s), so we simply return it here.
        return new TurnResponse(session.getId(), responseToSend, imageUrl, false);
    }

    /**
     * Undo the last generated picture: restores the previous living canvas so the child can
     * step back one picture. The current picture is discarded and the previously stored one
     * becomes the living canvas again (a single level of undo). When no previous picture
     * exists, the current picture is left untouched.
     *
     * @param sessionId the session to undo the last picture for
     * @return the picture now shown as a browser-ready {@code data:} URL (or null when none)
     */
    @Transactional
    public TurnResponse undoLastImage(UUID sessionId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown session: " + sessionId));
        if (session.getPreviousSceneImageData() != null
                && !session.getPreviousSceneImageData().isBlank()) {
            // Roll back one step: the previous picture becomes the current living canvas and
            // there is nothing further to undo (single-level undo).
            session.setSceneImageData(session.getPreviousSceneImageData());
            session.setSceneImageMediaType(session.getPreviousSceneImageMediaType());
            session.setPreviousSceneImageData(null);
            session.setPreviousSceneImageMediaType(null);
            sessionRepository.save(session);
            log.info("Undid last generated picture for session={}", sessionId);
        }
        String imageUrl = asDataUrl(session.getSceneImageData(), session.getSceneImageMediaType());
        return new TurnResponse(session.getId(), null, imageUrl, false);
    }

    /**
     * Stores the freshly generated picture as the session's living canvas: splits the
     * {@code data:<mime>;base64,<data>} URL back into raw base64 + MIME and persists it.
     * The picture it replaces is kept as the {@code previous} picture so the child can undo
     * one step. Only the generated picture is ever stored (uploads/previous images are not).
     */
    private void storeGeneratedScene(Session session, String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) {
            return;
        }
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            return;
        }
        String meta = dataUrl.substring("data:".length(), comma); // e.g. "image/png;base64"
        int semicolon = meta.indexOf(';');
        String mime = semicolon >= 0 ? meta.substring(0, semicolon) : meta;
        // Preserve the current picture as the previous one so the child can undo one step.
        session.setPreviousSceneImageData(session.getSceneImageData());
        session.setPreviousSceneImageMediaType(session.getSceneImageMediaType());
        session.setSceneImageData(dataUrl.substring(comma + 1));
        session.setSceneImageMediaType(mime.isBlank() ? "image/png" : mime);
        sessionRepository.save(session);
    }

    /** Rebuilds a browser-ready {@code data:} URL from raw base64 + media type, or null if absent. */
    private static String asDataUrl(String base64, String mediaType) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        String mime = (mediaType == null || mediaType.isBlank()) ? "image/png" : mediaType;
        return "data:" + mime + ";base64," + base64;
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