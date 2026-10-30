package com.example.doanchuyennganh.exam;

import com.example.doanchuyennganh.account.security.AccountPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import jakarta.validation.Valid;
import static com.example.doanchuyennganh.exam.AttemptContracts.*;

@RestController
@RequestMapping("/api/v1/student")
public class StudentAttemptController {
    private final ExamAttemptService service;
    public StudentAttemptController(ExamAttemptService service) { this.service = service; }

    @PostMapping("/attempts/{attemptId}/submit")
    public ResponseEntity<ResultResponse> submit(@AuthenticationPrincipal AccountPrincipal actor,
            @PathVariable Long attemptId, @RequestBody(required = false) byte[] body) {
        if (body != null && body.length != 0) throw new InvalidExamRequestException("Request nộp bài phải có body rỗng.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.submit(actor, attemptId));
    }

    @GetMapping("/attempts/{attemptId}/result")
    public ResponseEntity<ResultResponse> result(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable Long attemptId) {
        var result = service.result(actor, attemptId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.unwrap());
    }

    @PutMapping("/attempts/{attemptId}/answers/{questionId}")
    public ResponseEntity<SaveAnswerResponse> save(@AuthenticationPrincipal AccountPrincipal actor,
            @PathVariable Long attemptId, @PathVariable Long questionId, @Valid @RequestBody SaveAnswerRequest request) {
        var result = service.saveAnswer(actor, attemptId, questionId, request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.unwrap());
    }

    @PostMapping("/attempts/{attemptId}/timeout")
    public ResponseEntity<TimeoutResponse> timeout(@AuthenticationPrincipal AccountPrincipal actor,
            @PathVariable Long attemptId, @RequestBody(required = false) byte[] body) {
        if (body != null && body.length != 0) throw new InvalidExamRequestException("Request hết giờ phải có body rỗng.");
        var result = service.timeout(actor, attemptId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.unwrap());
    }

    @PostMapping("/exams/{examId}/attempt")
    public ResponseEntity<AttemptResponse> start(@AuthenticationPrincipal AccountPrincipal actor,
            @PathVariable Long examId, @RequestBody(required = false) byte[] body) {
        if (body != null && body.length != 0) {
            throw new InvalidExamRequestException("Request bắt đầu lượt thi phải có body rỗng.");
        }
        var result = service.start(actor, examId);
        var response = ResponseEntity.status(result.created() ? 201 : 200).cacheControl(CacheControl.noStore());
        if (result.created()) response.location(URI.create("/api/v1/student/attempts/" + result.body().attemptId()));
        return response.body(result.body());
    }

    @GetMapping("/exams/{examId}/attempt")
    public ResponseEntity<AttemptResponse> current(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable Long examId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.current(actor, examId));
    }

    @GetMapping("/attempts/{attemptId}")
    public ResponseEntity<AttemptResponse> get(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable Long attemptId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(actor, attemptId));
    }
}
