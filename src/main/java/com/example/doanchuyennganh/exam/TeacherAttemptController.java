package com.example.doanchuyennganh.exam;

import com.example.doanchuyennganh.account.security.AccountPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.example.doanchuyennganh.exam.AttemptContracts.*;
import static com.example.doanchuyennganh.exam.ExamContracts.PageResponse;

@RestController @RequestMapping("/api/v1/teacher/exams/{examId}/attempts")
public class TeacherAttemptController {
    private final ManagedAttemptService service;
    public TeacherAttemptController(ManagedAttemptService service) { this.service = service; }
    @GetMapping
    public ResponseEntity<PageResponse<ManagedAttemptResponse>> list(@AuthenticationPrincipal AccountPrincipal actor,
            @PathVariable Long examId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(actor, examId, page, size));
    }
    @GetMapping("/{attemptId}/result")
    public ResponseEntity<ManagedResultResponse> result(@AuthenticationPrincipal AccountPrincipal actor,
            @PathVariable Long examId, @PathVariable Long attemptId) {
        var result = service.result(actor, examId, attemptId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.unwrap());
    }
}
