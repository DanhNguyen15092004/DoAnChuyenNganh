package com.example.doanchuyennganh.account.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Profile("(local | test) & !prod")
public class RoleProbeController {
    @GetMapping("/api/v1/student/probe")
    public Map<String, String> student() { return Map.of("group", "student"); }

    @GetMapping("/api/v1/teacher/probe")
    public Map<String, String> teacher() { return Map.of("group", "teacher"); }

    @GetMapping("/api/v1/admin/probe")
    public Map<String, String> admin() { return Map.of("group", "admin"); }
}
