package com.adventureclub.controller;

import com.adventureclub.domain.AuthRequest;
import com.adventureclub.domain.AuthResponse;
import com.adventureclub.domain.ChangePasswordRequest;
import com.adventureclub.domain.User;
import com.adventureclub.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;

/**
 * Real backend authentication for the Adventure Club "heroes".
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /auth/register} — create a new hero (unique name + secret word),
 *       then sign them in immediately.</li>
 *   <li>{@code POST /auth/login} — sign an existing hero in.</li>
 *   <li>{@code POST /auth/logout} — end the session.</li>
 *   <li>{@code POST /auth/change-password} — change the signed-in hero's secret word.</li>
 *   <li>{@code GET  /auth/me} — who is signed in (or 401).</li>
 * </ul>
 *
 * <p>On a successful register/login the {@link Authentication} is stored in the
 * HTTP session via {@link SecurityContextRepository}, so the JSESSIONID cookie
 * keeps the hero signed in on later requests (including the gated {@code /session/**}).
 */
@RestController
@RequestMapping("/auth")
@CrossOrigin
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthController(UserRepository users,
                          PasswordEncoder passwordEncoder,
                          AuthenticationManager authenticationManager) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody AuthRequest req,
                                                 HttpServletRequest request,
                                                 HttpServletResponse response) {
        String username = req.username().trim();
        if (username.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please choose a hero name.");
        }
        if (users.existsByUsernameIgnoreCase(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That name is already taken — try logging in instead!");
        }
        users.save(new User(username, passwordEncoder.encode(req.password())));
        // Sign the new hero in right away.
        return authenticateAndRespond(username, req.password(), request, response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AuthRequest req,
                                              HttpServletRequest request,
                                              HttpServletResponse response) {
        return authenticateAndRespond(req.username().trim(), req.password(), request, response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> me(Principal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in.");
        }
        return ResponseEntity.ok(new AuthResponse(principal.getName()));
    }

    /**
     * Changes the signed-in hero's secret word. The current secret word is
     * re-verified against the stored BCrypt hash before the new one is saved.
     * The existing session stays valid (the JSESSIONID cookie is unchanged).
     */
    @PostMapping("/change-password")
    public ResponseEntity<AuthResponse> changePassword(@Valid @RequestBody ChangePasswordRequest req,
                                                       Principal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in.");
        }
        User user = users.findByUsernameIgnoreCase(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in."));
        if (!passwordEncoder.matches(req.currentPassword(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That's not your current secret word — try again!");
        }
        user.setPassword(passwordEncoder.encode(req.newPassword()));
        users.save(user);
        return ResponseEntity.ok(new AuthResponse(user.getUsername()));
    }

    private ResponseEntity<AuthResponse> authenticateAndRespond(String username,
                                                                String password,
                                                                HttpServletRequest request,
                                                                HttpServletResponse response) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            // Persist into the HTTP session so later requests stay authenticated.
            securityContextRepository.saveContext(context, request, response);

            return ResponseEntity.ok(new AuthResponse(authentication.getName()));
        } catch (BadCredentialsException | org.springframework.security.core.userdetails.UsernameNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Wrong name or secret word — try again!");
        }
    }
}
