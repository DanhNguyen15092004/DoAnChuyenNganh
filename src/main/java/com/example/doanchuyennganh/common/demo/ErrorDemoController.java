package com.example.doanchuyennganh.common.demo;

import com.example.doanchuyennganh.common.exception.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Technical examples for verifying error responses, available only in local/test. */
@Profile({"local", "test"})
@RestController
@RequestMapping("/api/examples/errors")
public class ErrorDemoController {
    @PostMapping("/validation")
    public void validation(@Valid @RequestBody ValidationRequest request) { }

    @GetMapping("/not-found")
    public void notFound() {
        throw new ResourceNotFoundException("Demo resource does not exist");
    }

    @GetMapping("/internal")
    public void internal() {
        throw new IllegalStateException("Demo internal diagnostic must never appear in the response");
    }

    public record ValidationRequest(@NotBlank String name) { }
}
