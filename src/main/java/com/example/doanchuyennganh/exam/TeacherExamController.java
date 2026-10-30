package com.example.doanchuyennganh.exam;

import com.example.doanchuyennganh.account.security.AccountPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import static com.example.doanchuyennganh.exam.ExamContracts.*;

@RestController @RequestMapping("/api/v1/teacher/exams")
public class TeacherExamController {
    private final ExamService service;
    public TeacherExamController(ExamService service) { this.service=service; }
    @PostMapping({"","/"})
    public ResponseEntity<TeacherExamResponse> create(@AuthenticationPrincipal AccountPrincipal actor, @Valid @RequestBody MetadataRequest request) {
        var body=service.create(actor,request);
        return ResponseEntity.created(URI.create("/api/v1/teacher/exams/"+body.id())).body(body);
    }
    @GetMapping({"","/"})
    public PageResponse<ExamSummary> list(@AuthenticationPrincipal AccountPrincipal actor,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) { return service.listManaged(actor,page,size); }
    @GetMapping("/{id}") public TeacherExamResponse get(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable Long id) { return service.getManaged(actor,id); }
    @PutMapping("/{id}") public TeacherExamResponse update(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable Long id,
            @Valid @RequestBody MetadataRequest request) { return service.update(actor,id,request); }
    @PutMapping("/{id}/questions") public TeacherExamResponse questions(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable Long id,
            @Valid @RequestBody QuestionsRequest request) { return service.replaceQuestions(actor,id,request); }
    @PostMapping("/{id}/publish") public TeacherExamResponse publish(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable Long id) { return service.publish(actor,id); }
    @PostMapping("/{id}/enrollments") public ResponseEntity<EnrollmentResponse> enroll(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable Long id,
            @Valid @RequestBody EnrollmentRequest request) {
        var result=service.enroll(actor,id,request); return ResponseEntity.status(result.created()?201:200).body(result.body());
    }
    @GetMapping("/{id}/enrollments") public PageResponse<EnrollmentResponse> enrollments(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable Long id,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.listEnrollments(actor,id,page,size); }
}
