package com.adventureclub.controller;

import com.adventureclub.domain.TurnRequest;
import com.adventureclub.domain.TurnResponse;
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
 *
 * @CrossOrigin allows the static index.html test page served on the same port
 * to make fetch() calls without CORS errors. Remove this in production and
 * configure CORS properly with WebMvcConfigurer.
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
}