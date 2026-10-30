package com.example.doanchuyennganh.account.auth;

import com.example.doanchuyennganh.account.EmailNormalizer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/register")
public class RegistrationController {
    private final RegistrationService registration;

    public RegistrationController(RegistrationService registration) { this.registration = registration; }

    @PostMapping
    public ResponseEntity<AuthController.AccountResponse> register(@Valid @RequestBody RegistrationRequest request) {
        var user = registration.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new AuthController.AccountResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole()));
    }

    // Handles a concurrent email collision only after the service transaction rolls back.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail duplicateEmail(DataIntegrityViolationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Email này đã được đăng ký.");
    }

    public record RegistrationRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password,
            @NotBlank @Size(max = 100) String displayName) {
        public RegistrationRequest {
            if (email != null) email = EmailNormalizer.normalize(email);
            if (displayName != null) displayName = displayName.trim();
        }
        @JsonAnySetter
        public void reject(String field, Object value) { throw new IllegalArgumentException("Unsupported registration field"); }

        @Override public String toString() { return "RegistrationRequest[credentials redacted]"; }
    }
}
