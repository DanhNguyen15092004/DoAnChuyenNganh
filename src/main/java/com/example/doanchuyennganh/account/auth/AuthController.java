package com.example.doanchuyennganh.account.auth;

import com.example.doanchuyennganh.account.EmailNormalizer;
import com.example.doanchuyennganh.account.Role;
import com.example.doanchuyennganh.account.security.AccountPrincipal;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contexts;

    public AuthController(AuthenticationManager authenticationManager, SessionAuthenticationStrategy sessionStrategy,
                          SecurityContextRepository contexts) {
        this.authenticationManager = authenticationManager;
        this.sessionStrategy = sessionStrategy;
        this.contexts = contexts;
    }

    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        // Reading getToken materializes Spring Security's deferred session token.
        return noStore(new CsrfResponse(token.getHeaderName(), token.getToken()));
    }

    @PostMapping("/login")
    public ResponseEntity<AccountResponse> login(@Valid @RequestBody LoginRequest credentials,
                                                HttpServletRequest request, HttpServletResponse response) {
        var authentication = authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
                credentials.email(), credentials.password()));
        if (authentication instanceof CredentialsContainer container) {
            container.eraseCredentials();
        }
        sessionStrategy.onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return noStore(AccountResponse.from((AccountPrincipal) authentication.getPrincipal()));
    }

    @GetMapping("/me")
    public ResponseEntity<AccountResponse> me(@AuthenticationPrincipal AccountPrincipal principal) {
        return noStore(AccountResponse.from(principal));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password) {
        public LoginRequest {
            if (email != null) { email = EmailNormalizer.normalize(email); }
        }
        @Override
        public String toString() { return "LoginRequest[credentials redacted]"; }
    }

    public record CsrfResponse(String headerName, String token) {
        @Override
        public String toString() { return "CsrfResponse[token redacted]"; }
    }

    public record AccountResponse(Long id, String email, String displayName, Role role) {
        static AccountResponse from(AccountPrincipal principal) {
            return new AccountResponse(principal.getId(), principal.getEmail(), principal.getDisplayName(), principal.getRole());
        }
    }
}
