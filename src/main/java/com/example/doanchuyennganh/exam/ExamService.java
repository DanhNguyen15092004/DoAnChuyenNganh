package com.example.doanchuyennganh.exam;

import com.example.doanchuyennganh.account.*;
import com.example.doanchuyennganh.account.security.AccountPrincipal;
import com.example.doanchuyennganh.common.exception.ResourceNotFoundException;
import jakarta.validation.Validator;
import org.springframework.data.domain.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import static com.example.doanchuyennganh.exam.ExamContracts.*;

@Service
@Transactional(readOnly=true)
public class ExamService {
    private final ExamRepository exams;
    private final ExamQuestionRepository questions;
    private final QuestionOptionRepository options;
    private final ExamEnrollmentRepository enrollments;
    private final ExamConsentRepository consents;
    private final UserRepository users;
    private final Validator validator;
    private final Clock clock;
    private final ExamConfiguration.ConsentPolicy policy;

    public ExamService(ExamRepository exams, ExamQuestionRepository questions, QuestionOptionRepository options,
            ExamEnrollmentRepository enrollments, ExamConsentRepository consents, UserRepository users,
            Validator validator, Clock clock, ExamConfiguration.ConsentPolicy policy) {
        this.exams=exams; this.questions=questions; this.options=options; this.enrollments=enrollments;
        this.consents=consents; this.users=users; this.validator=validator; this.clock=clock; this.policy=policy;
    }

    @Transactional
    public TeacherExamResponse create(AccountPrincipal actor, MetadataRequest request) {
        requireManager(actor); validateMetadata(request);
        var exam = new Exam(); exam.ownerId=actor.getId(); exam.status=ExamStatus.DRAFT;
        exam.createdAt=now(); exam.updatedAt=exam.createdAt; applyMetadata(exam,request);
        exam.consentVersion=policy.version(); exam.consentText=policy.text();
        exams.saveAndFlush(exam); return teacherResponse(exam);
    }

    public PageResponse<ExamSummary> listManaged(AccountPrincipal actor, int page, int size) {
        requireManager(actor); var paging=page(page,size,"createdAt");
        return PageResponse.from(actor.getRole()==Role.ADMIN ? exams.findAll(paging) : exams.findByOwnerId(actor.getId(),paging), this::summary);
    }

    public TeacherExamResponse getManaged(AccountPrincipal actor, Long id) { return teacherResponse(managed(actor,id,false)); }

    @Transactional
    public TeacherExamResponse update(AccountPrincipal actor, Long id, MetadataRequest request) {
        var exam=managed(actor,id,true); requireDraft(exam); validateMetadata(request);
        applyMetadata(exam,request); exam.updatedAt=now(); exams.flush(); return teacherResponse(exam);
    }

    @Transactional
    public TeacherExamResponse replaceQuestions(AccountPrincipal actor, Long id, QuestionsRequest request) {
        var exam=managed(actor,id,true); requireDraft(exam); validateQuestions(request);
        // No deletion occurs before validation of the entire replacement succeeds.
        options.deleteForExam(id); questions.deleteForExam(id);
        Instant time=now();
        for (var input : request.questions()) {
            var q=new ExamQuestion(); q.examId=id; q.content=input.content(); q.position=input.position();
            q.createdAt=time; q.updatedAt=time; questions.saveAndFlush(q);
            for (var inputOption : input.options()) {
                var option=new QuestionOption(); option.questionId=q.id; option.content=inputOption.content();
                option.position=inputOption.position(); option.correct=inputOption.correct(); options.save(option);
            }
        }
        exam.updatedAt=time; exams.flush(); return teacherResponse(exam);
    }

    @Transactional
    public TeacherExamResponse publish(AccountPrincipal actor, Long id) {
        var exam=managed(actor,id,true);
        if (exam.status==ExamStatus.PUBLISHED) { return teacherResponse(exam); }
        validateMetadata(new MetadataRequest(exam.title,exam.description,exam.durationMinutes,exam.opensAt,exam.closesAt));
        var content=questionResponses(id);
        if (content.isEmpty()) { throw new ExamConflictException("An exam must have at least one valid question before publication."); }
        validateQuestions(new QuestionsRequest(content.stream().map(q -> new QuestionRequest(q.content(),q.position(),
                q.options().stream().map(o -> new OptionRequest(o.content(),o.position(),o.correct())).toList())).toList()));
        Instant time=now();
        if (!exam.closesAt.isAfter(time)) { throw new ExamConflictException("The exam has already closed."); }
        exam.consentVersion=policy.version(); exam.consentText=policy.text();
        exam.status=ExamStatus.PUBLISHED; exam.publishedAt=time; exam.updatedAt=exam.publishedAt;
        exams.flush(); return teacherResponse(exam);
    }

    @Transactional
    public Creation<EnrollmentResponse> enroll(AccountPrincipal actor, Long id, EnrollmentRequest request) {
        var exam=managed(actor,id,true); Instant time=now(); requireOpenForEnrollment(exam,time); validate(request);
        var student=users.findById(request.studentId()).filter(u -> u.isEnabled() && u.getRole()==Role.STUDENT)
                .orElseThrow(() -> new InvalidExamRequestException("Enrollment requires an enabled STUDENT account."));
        var existing=enrollments.findByExamIdAndStudentId(id,student.getId());
        if (existing.isPresent()) { return new Creation<>(false,enrollmentResponse(existing.get(),student)); }
        var enrollment=new ExamEnrollment(); enrollment.examId=id; enrollment.studentId=student.getId();
        enrollment.enrolledBy=actor.getId(); enrollment.enrolledAt=time; enrollments.saveAndFlush(enrollment);
        return new Creation<>(true,enrollmentResponse(enrollment,student));
    }

    public PageResponse<EnrollmentResponse> listEnrollments(AccountPrincipal actor, Long id, int page, int size) {
        managed(actor,id,false);
        var result=enrollments.findByExamId(id,page(page,size,"enrolledAt"));
        var accounts=users.findAllById(result.getContent().stream().map(n -> n.studentId).toList()).stream()
                .collect(Collectors.toMap(User::getId,u -> u));
        return PageResponse.from(result,n -> enrollmentResponse(n,accounts.get(n.studentId)));
    }

    public PageResponse<StudentExamResponse> listStudent(AccountPrincipal actor, int page, int size) {
        requireStudent(actor); Instant time=now();
        return PageResponse.from(exams.findEnrolled(actor.getId(),page(page,size,"createdAt")),
                e -> studentResponse(e,enrollments.findByExamIdAndStudentId(e.id,actor.getId()).orElseThrow(this::notFound),time));
    }

    public StudentExamResponse getStudent(AccountPrincipal actor, Long id) {
        requireStudent(actor);
        var exam=exams.findById(id).filter(e -> e.status==ExamStatus.PUBLISHED).orElseThrow(this::notFound);
        var enrollment=enrollments.findByExamIdAndStudentId(id,actor.getId()).orElseThrow(this::notFound);
        return studentResponse(exam,enrollment,now());
    }

    @Transactional
    public Creation<ConsentResponse> acceptConsent(AccountPrincipal actor, Long id, ConsentRequest request) {
        requireStudent(actor);
        var exam=exams.lockById(id).filter(e -> e.status==ExamStatus.PUBLISHED).orElseThrow(this::notFound);
        var enrollment=enrollments.findByExamIdAndStudentId(id,actor.getId()).orElseThrow(this::notFound);
        validate(request); Instant time=now(); requireOpenForEnrollment(exam,time);
        if (!exam.consentVersion.equals(request.consentVersion())) { throw new ExamConflictException("The consent version does not match the published exam."); }
        var existing=consents.findByEnrollmentId(enrollment.id);
        if (existing.isPresent()) {
            if (!existing.get().consentVersion.equals(exam.consentVersion)) { throw new ExamConflictException("The recorded consent has a different version."); }
            return new Creation<>(false,consentResponse(existing.get()));
        }
        var consent=new ExamConsent(); consent.enrollmentId=enrollment.id; consent.consentVersion=exam.consentVersion;
        consent.acceptedAt=time; consents.saveAndFlush(consent);
        return new Creation<>(true,consentResponse(consent));
    }

    private Exam managed(AccountPrincipal actor, Long id, boolean lock) {
        requireManager(actor);
        var exam=(lock ? exams.lockById(id) : actor.getRole()==Role.ADMIN ? exams.findById(id)
                : exams.findByIdAndOwnerId(id,actor.getId())).orElseThrow(this::notFound);
        if (actor.getRole()!=Role.ADMIN && !actor.getId().equals(exam.ownerId)) { throw notFound(); }
        return exam;
    }

    private void requireManager(AccountPrincipal actor) {
        if (actor==null || !actor.isEnabled() || (actor.getRole()!=Role.TEACHER && actor.getRole()!=Role.ADMIN)) {
            throw new AccessDeniedException("Exam management requires TEACHER or ADMIN.");
        }
    }
    private void requireDraft(Exam exam) {
        if (exam.status!=ExamStatus.DRAFT) { throw new ExamConflictException("Published exams are immutable."); }
    }
    private void requireStudent(AccountPrincipal actor) {
        if (actor==null || !actor.isEnabled() || actor.getRole()!=Role.STUDENT) { throw new AccessDeniedException("STUDENT role is required."); }
    }
    private void requireOpenForEnrollment(Exam exam, Instant time) {
        if (exam.status!=ExamStatus.PUBLISHED || !exam.closesAt.isAfter(time)) {
            throw new ExamConflictException("Enrollment requires a published exam that has not closed.");
        }
    }
    private void validateMetadata(MetadataRequest request) {
        validate(request);
        Instant opens=precision(request.opensAt()), closes=precision(request.closesAt());
        if (!opens.isBefore(closes) || Duration.between(opens,closes).compareTo(Duration.ofMinutes(request.durationMinutes()))<0) {
            throw new InvalidExamRequestException("The time window must be positive and at least as long as the exam duration.");
        }
    }
    private void validateQuestions(QuestionsRequest request) {
        validate(request); var positions=new HashSet<Integer>();
        for (var q : request.questions()) {
            if (!positions.add(q.position())) { throw new InvalidExamRequestException("Question positions must be unique."); }
            var optionPositions=new HashSet<Integer>();
            for (var o : q.options()) {
                if (!optionPositions.add(o.position())) { throw new InvalidExamRequestException("Option positions must be unique within a question."); }
            }
            if (q.options().stream().filter(OptionRequest::correct).count()!=1) {
                throw new InvalidExamRequestException("Every question requires exactly one correct option.");
            }
        }
    }
    private <T> void validate(T request) {
        if (request==null || !validator.validate(request).isEmpty()) { throw new InvalidExamRequestException("Invalid exam request."); }
    }
    private void applyMetadata(Exam exam, MetadataRequest request) {
        exam.title=request.title(); exam.description=request.description(); exam.durationMinutes=request.durationMinutes();
        exam.opensAt=precision(request.opensAt()); exam.closesAt=precision(request.closesAt());
    }
    private Pageable page(int page, int size, String sort) {
        if (page<0 || size<1 || size>100) { throw new InvalidExamRequestException("page must be non-negative and size must be between 1 and 100."); }
        return PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,sort,"id"));
    }
    private Instant precision(Instant time) { return time.truncatedTo(ChronoUnit.MICROS); }
    private Instant now() { return precision(clock.instant()); }
    private ResourceNotFoundException notFound() { return new ResourceNotFoundException("Exam not found."); }
    private ExamSummary summary(Exam e) { return new ExamSummary(e.id,e.ownerId,e.title,e.description,e.durationMinutes,e.opensAt,e.closesAt,e.status,e.publishedAt,e.createdAt,e.version); }
    private TeacherExamResponse teacherResponse(Exam e) {
        return new TeacherExamResponse(e.id,e.ownerId,e.title,e.description,e.durationMinutes,e.opensAt,e.closesAt,e.status,
                e.publishedAt,e.consentVersion,e.consentText,e.version,e.createdAt,e.updatedAt,questionResponses(e.id));
    }
    private List<QuestionResponse> questionResponses(Long id) {
        var content=questions.findByExamIdOrderByPositionAsc(id);
        if (content.isEmpty()) { return List.of(); }
        var choices=options.findByQuestionIdInOrderByPositionAsc(content.stream().map(q -> q.id).toList()).stream()
                .collect(Collectors.groupingBy(o -> o.questionId));
        return content.stream().map(q -> new QuestionResponse(q.id,q.content,q.position,choices.getOrDefault(q.id,List.of()).stream()
                .map(o -> new OptionResponse(o.id,o.content,o.position,o.correct)).toList())).toList();
    }
    private EnrollmentResponse enrollmentResponse(ExamEnrollment n, User student) {
        var consent=consents.findByEnrollmentId(n.id);
        return new EnrollmentResponse(n.id,n.examId,new StudentResponse(student.getId(),student.getEmail(),student.getDisplayName()),
                n.enrolledBy,n.enrolledAt,consent.isPresent(),consent.map(c -> c.acceptedAt).orElse(null));
    }
    private StudentExamResponse studentResponse(Exam e, ExamEnrollment enrollment, Instant time) {
        Availability availability=time.isBefore(e.opensAt)?Availability.UPCOMING:time.isBefore(e.closesAt)?Availability.OPEN:Availability.CLOSED;
        boolean accepted=consents.findByEnrollmentId(enrollment.id).filter(c -> c.consentVersion.equals(e.consentVersion)).isPresent();
        return new StudentExamResponse(e.id,e.title,e.description,e.durationMinutes,e.opensAt,e.closesAt,
                questions.countByExamId(e.id),time,availability,e.consentVersion,e.consentText,accepted);
    }
    private ConsentResponse consentResponse(ExamConsent c) { return new ConsentResponse(c.id,c.enrollmentId,c.consentVersion,c.acceptedAt); }
}
