package com.example.doanchuyennganh.exam;

import com.example.doanchuyennganh.account.security.AccountPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.example.doanchuyennganh.exam.ExamContracts.*;

@RestController @RequestMapping("/api/v1/student/exams")
public class StudentExamController {
    private final ExamService service;
    public StudentExamController(ExamService service) { this.service=service; }
    @GetMapping({"","/"}) public PageResponse<StudentExamResponse> list(@AuthenticationPrincipal AccountPrincipal actor,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.listStudent(actor,page,size); }
    @GetMapping("/{id}") public StudentExamResponse get(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable Long id) { return service.getStudent(actor,id); }
    @PostMapping("/{id}/consent") public ResponseEntity<ConsentResponse> consent(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable Long id,
            @Valid @RequestBody ConsentRequest request) {
        var result=service.acceptConsent(actor,id,request); return ResponseEntity.status(result.created()?201:200).body(result.body());
    }
}
