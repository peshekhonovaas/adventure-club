package com.adventureclub.controller;

import com.adventureclub.domain.TurnRequest;
import com.adventureclub.domain.TurnResponse;
import com.adventureclub.domain.UndoRequest;
import com.adventureclub.orchestrator.Orchestrator;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * One endpoint. That is all week 1 needs.
 *
 * POST /session/turn
 *   Body:  { "sessionId": null, "interests": "dragons, pokemon", "childMessage": "hello" }
 *   Returns: { "sessionId": "uuid", "storyText": "...", "blocked": false }
 *
 * On the first turn, send sessionId=null — the server creates the session and
 * returns its id. Send that id on every subsequent turn.
 */
@RestController
@RequestMapping("/session")
@CrossOrigin
public class SessionController {

    private final Orchestrator orchestrator;

    public SessionController(Orchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/turn")
    public ResponseEntity<TurnResponse> turn(@Valid @RequestBody TurnRequest request) {
        TurnResponse response = orchestrator.processTurn(request);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /session/undo
     *   Body:  { "sessionId": "uuid" }
     *   Returns: { "sessionId": "uuid", "imageUrl": "data:...", "blocked": false }
     *
     * Undoes the last generated picture, restoring the previous one (single-level undo).
     * storyText is always null here — only the picture changes.
     */
    @PostMapping("/undo")
    public ResponseEntity<TurnResponse> undo(@Valid @RequestBody UndoRequest request) {
        TurnResponse response = orchestrator.undoLastImage(request.sessionId());
        return ResponseEntity.ok(response);
    }
}