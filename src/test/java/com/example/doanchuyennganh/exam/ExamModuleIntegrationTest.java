package com.example.doanchuyennganh.exam;

import com.example.doanchuyennganh.account.*;
import com.example.doanchuyennganh.account.security.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static com.example.doanchuyennganh.exam.ExamContracts.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test") @Testcontainers
@Import(ExamModuleIntegrationTest.TimeConfiguration.class)
class ExamModuleIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl); r.add("spring.datasource.username",MYSQL::getUsername);
        r.add("spring.datasource.password",MYSQL::getPassword);
    }
    static final Instant NOW=Instant.parse("2030-01-01T00:00:00Z");
    static final String BASE="/api/v1/teacher/exams";
    static final String PASSWORD=" Exam-Fixture-Only ";
    @Autowired ExamService service;
    @Autowired UserRepository users;
    @Autowired DatabaseUserDetailsService details;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired AdjustableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @LocalServerPort int port;

    @BeforeEach void fixtures() {
        clock.set(NOW);
        fixture("teacher.a",Role.TEACHER,true); fixture("teacher.b",Role.TEACHER,true);
        fixture("admin",Role.ADMIN,true); fixture("student",Role.STUDENT,true);
        fixture("other.student",Role.STUDENT,true); fixture("disabled.student",Role.STUDENT,false);
    }
    void fixture(String name,Role role,boolean enabled) {
        if(users.findByEmail(email(name)).isEmpty()) { users.saveAndFlush(new User(email(name),encoder.encode(PASSWORD),name,role,enabled)); }
    }
    AccountPrincipal actor(String name) { return details.loadUserByUsername(email(name)); }
    Long userId(String name) { return users.findByEmail(email(name)).orElseThrow().getId(); }
    static String email(String name) { return name+".exam@example.test"; }
    MetadataRequest metadata(String title) { return new MetadataRequest(title,"Description",10,NOW.plusSeconds(3600),NOW.plusSeconds(7200)); }
    QuestionsRequest validQuestions(String content) {
        return new QuestionsRequest(List.of(new QuestionRequest(content,1,List.of(new OptionRequest("A",1,true),new OptionRequest("B",2,false)))));
    }
    TeacherExamResponse draft(String owner) { return service.create(actor(owner),metadata("Exam "+UUID.randomUUID())); }
    TeacherExamResponse prepared(String owner) {
        var e=draft(owner); return service.replaceQuestions(actor(owner),e.id(),validQuestions("Original question"));
    }
    TeacherExamResponse published(String owner) { return service.publish(actor(owner),prepared(owner).id()); }

    @Test void createsDraftWithPrincipalOwnerAndRejectsServerFieldsAndBadMetadata() throws Exception {
        var teacher=login("teacher.a");
        var created=teacher.send("POST",BASE,metadata("Created over HTTP")); expect(created,201);
        var body=json(created); assertThat(body.get("ownerId").asLong()).isEqualTo(userId("teacher.a"));
        assertThat(body.get("status").asText()).isEqualTo("DRAFT");
        assertThat(created.headers().firstValue("Location")).hasValue(BASE+"/"+body.get("id").asLong());
        for(String field:List.of("ownerId","status","publishedAt","consentText","consentVersion")) {
            ObjectNode input=mapper.valueToTree(metadata("Illegal server field")); input.put(field,"untrusted");
            problem(teacher.send("POST",BASE,input),400);
        }
        for(var invalid:List.of(new MetadataRequest(" ",null,1,NOW,NOW.plusSeconds(60)),
                new MetadataRequest("x".repeat(201),null,1,NOW,NOW.plusSeconds(60)),
                new MetadataRequest("X","x".repeat(5001),1,NOW,NOW.plusSeconds(60)),
                new MetadataRequest("X",null,0,NOW,NOW.plusSeconds(60)),
                new MetadataRequest("X",null,301,NOW,NOW.plusSeconds(30000)),
                new MetadataRequest("X",null,2,NOW,NOW.plusSeconds(60)),
                new MetadataRequest("X",null,1,NOW,NOW))) { problem(teacher.send("POST",BASE,invalid),400); }
    }

    @Test void ownershipIsCheckedForReadsMutationsAndEnrollmentsAndAdminCanManageAll() throws Exception {
        var exam=prepared("teacher.b"); var teacher=login("teacher.a"); String path=BASE+"/"+exam.id();
        problem(teacher.send("GET",path,null),404);
        problem(teacher.send("PUT",path,metadata("Not mine")),404);
        problem(teacher.send("PUT",path+"/questions",validQuestions("Not mine")),404);
        problem(teacher.send("POST",path+"/publish",null),404);
        problem(teacher.send("POST",path+"/enrollments",new EnrollmentRequest(userId("student"))),404);
        problem(teacher.send("GET",path+"/enrollments",null),404);
        assertThatThrownBy(()->service.getManaged(actor("teacher.a"),exam.id())).isInstanceOf(com.example.doanchuyennganh.common.exception.ResourceNotFoundException.class);
        var admin=login("admin"); expect(admin.send("GET",path,null),200);
        expect(admin.send("PUT",path,metadata("Admin edited")),200);
        assertThat(service.getManaged(actor("admin"),exam.id()).ownerId()).isEqualTo(userId("teacher.b"));
        expect(admin.send("POST",path+"/publish",null),200);
        expect(admin.send("POST",path+"/enrollments",new EnrollmentRequest(userId("student"))),201);
        var adminCreated=service.create(actor("admin"),metadata("Admin owned"));
        assertThat(adminCreated.ownerId()).isEqualTo(userId("admin"));
    }

    @Test void studentAndAnonymousCannotUseTeacherApisAndCsrfIsStillRequired() throws Exception {
        var student=login("student"); problem(student.send("GET",BASE,null),403);
        problem(student.send("POST",BASE,metadata("Forbidden")),403);
        problem(new Client().send("GET",BASE,null),401);
        var teacher=login("teacher.a"); var exam=draft("teacher.a");
        String token=teacher.token; teacher.token=null;
        problem(teacher.send("POST",BASE,metadata("No csrf")),403);
        problem(teacher.send("PUT",BASE+"/"+exam.id(),metadata("No csrf")),403);
        teacher.token="invalid-fixture-token";
        problem(teacher.send("POST",BASE,metadata("Bad csrf")),403);
        problem(teacher.send("PUT",BASE+"/"+exam.id()+"/questions",validQuestions("Bad csrf")),403);
        teacher.token=token;
    }

    @Test void replacementValidatesWholeRequestAndPreservesOldQuestionsOnEveryFailure() throws Exception {
        var exam=prepared("teacher.a"); var principal=actor("teacher.a");
        var good=validQuestions("Replacement").questions().getFirst();
        var invalid=new ArrayList<QuestionsRequest>();
        invalid.add(new QuestionsRequest(List.of(new QuestionRequest("Q",1,List.of(new OptionRequest("A",1,true))))));
        invalid.add(new QuestionsRequest(List.of(new QuestionRequest("Q",1,java.util.stream.IntStream.rangeClosed(1,7).mapToObj(i->new OptionRequest("O",i,i==1)).toList()))));
        invalid.add(new QuestionsRequest(List.of(new QuestionRequest("Q",1,List.of(new OptionRequest("A",1,false),new OptionRequest("B",2,false))))));
        invalid.add(new QuestionsRequest(List.of(new QuestionRequest("Q",1,List.of(new OptionRequest("A",1,true),new OptionRequest("B",2,true))))));
        invalid.add(new QuestionsRequest(List.of(good,good)));
        invalid.add(new QuestionsRequest(List.of(new QuestionRequest("Q",0,good.options()))));
        invalid.add(new QuestionsRequest(List.of(new QuestionRequest("Q",1,List.of(new OptionRequest("A",1,true),new OptionRequest("B",1,false))))));
        invalid.add(new QuestionsRequest(List.of(new QuestionRequest("Q",1,List.of(new OptionRequest("A",0,true),new OptionRequest("B",2,false))))));
        invalid.add(new QuestionsRequest(List.of(new QuestionRequest(" ",1,good.options()))));
        invalid.add(new QuestionsRequest(java.util.stream.IntStream.rangeClosed(1,101).mapToObj(i->new QuestionRequest("Q",i,good.options())).toList()));
        for(var request:invalid) {
            assertThatThrownBy(()->service.replaceQuestions(principal,exam.id(),request)).isInstanceOf(InvalidExamRequestException.class);
            assertThat(service.getManaged(principal,exam.id()).questions()).isEqualTo(exam.questions());
        }
        var teacher=login("teacher.a"); ObjectNode withId=mapper.valueToTree(validQuestions("Illegal id"));
        ((ObjectNode)withId.withArray("questions").get(0)).put("id",999);
        problem(teacher.send("PUT",BASE+"/"+exam.id()+"/questions",withId),400);
        assertThat(service.getManaged(principal,exam.id()).questions()).isEqualTo(exam.questions());
        assertThat(service.replaceQuestions(principal,exam.id(),new QuestionsRequest(List.of())).questions()).isEmpty();
    }

    @Test void publicationRejectsEmptyOrClosedDraftAndIsIdempotentAndFreezesContent() throws Exception {
        var principal=actor("teacher.a"); var empty=draft("teacher.a");
        assertThatThrownBy(()->service.publish(principal,empty.id())).isInstanceOf(ExamConflictException.class);
        var past=service.create(principal,new MetadataRequest("Closed",null,1,NOW.minusSeconds(120),NOW));
        service.replaceQuestions(principal,past.id(),validQuestions("Past"));
        assertThatThrownBy(()->service.publish(principal,past.id())).isInstanceOf(ExamConflictException.class);
        var exam=published("teacher.a"); clock.set(NOW.plusSeconds(1));
        assertThat(service.publish(principal,exam.id()).publishedAt()).isEqualTo(exam.publishedAt());
        var teacher=login("teacher.a"); String path=BASE+"/"+exam.id();
        problem(teacher.send("PUT",path,metadata("Frozen")),409);
        problem(teacher.send("PUT",path+"/questions",new QuestionsRequest(List.of())),409);
        var after=service.getManaged(principal,exam.id());
        assertThat(after.questions()).isEqualTo(exam.questions());
        assertThat(after.consentVersion()).isEqualTo(exam.consentVersion());
        assertThat(after.consentText()).isEqualTo(exam.consentText());
    }

    @Test void enrollmentRequiresPublishedNonClosedExamAndEnabledStudentAndIsIdempotent() throws Exception {
        var teacher=login("teacher.a"); var draft=draft("teacher.a");
        problem(teacher.send("POST",BASE+"/"+draft.id()+"/enrollments",new EnrollmentRequest(userId("student"))),409);
        var exam=published("teacher.a"); String path=BASE+"/"+exam.id()+"/enrollments";
        for(Long id:List.of(userId("teacher.b"),userId("disabled.student"),Long.MAX_VALUE)) {
            problem(teacher.send("POST",path,new EnrollmentRequest(id)),400);
        }
        var first=teacher.send("POST",path,new EnrollmentRequest(userId("student"))); expect(first,201);
        var repeated=teacher.send("POST",path,new EnrollmentRequest(userId("student"))); expect(repeated,200);
        assertThat(json(first)).isEqualTo(json(repeated));
        var list=teacher.send("GET",path,null); expect(list,200);
        assertThat(json(list).get("totalElements").asLong()).isEqualTo(1);
        assertThat(list.body().contains("password") || list.body().contains("authorities")).isFalse();
        clock.set(exam.closesAt()); problem(teacher.send("POST",path,new EnrollmentRequest(userId("other.student"))),409);
    }

    @Test void managedListsAreScopedPagedAndStablyOrdered() throws Exception {
        var a=draft("teacher.a"); var b=draft("teacher.a"); var foreign=draft("teacher.b");
        var teacher=login("teacher.a"); var response=teacher.send("GET",BASE+"?size=100",null); expect(response,200);
        var content=json(response).get("content");
        assertThat(content.get(0).get("id").asLong()).isEqualTo(b.id());
        assertThat(content.get(1).get("id").asLong()).isEqualTo(a.id());
        content.forEach(e->assertThat(e.get("ownerId").asLong()).isEqualTo(userId("teacher.a")));
        problem(teacher.send("GET",BASE+"?size=101",null),400);
        problem(teacher.send("GET",BASE+"?page=-1",null),400);
        var admin=service.listManaged(actor("admin"),0,100);
        assertThat(admin.content().stream().map(ExamSummary::id)).contains(foreign.id());
    }

    @Test void studentVisibilityRequiresPublishedEnrollmentAndNeverDisclosesQuestionContent() throws Exception {
        var exam=published("teacher.a"); var other=published("teacher.a"); var draft=draft("teacher.a");
        service.enroll(actor("teacher.a"),exam.id(),new EnrollmentRequest(userId("student")));
        service.enroll(actor("teacher.a"),other.id(),new EnrollmentRequest(userId("other.student")));
        // Fixture with a legacy draft enrollment proves that enrollment alone is insufficient.
        jdbc.update("INSERT INTO exam_enrollments(exam_id,student_id,enrolled_by,enrolled_at) VALUES (?,?,?,?)",
                draft.id(),userId("student"),userId("teacher.a"),java.sql.Timestamp.from(NOW));
        var student=login("student"); String base="/api/v1/student/exams";
        problem(student.send("GET",base+"/"+other.id(),null),404);
        problem(student.send("GET",base+"/"+draft.id(),null),404);
        problem(student.send("GET",base+"/"+Long.MAX_VALUE,null),404);
        var detail=student.send("GET",base+"/"+exam.id(),null); expect(detail,200); assertStudentShape(json(detail));
        assertThat(json(detail).get("availability").asText()).isEqualTo("UPCOMING");
        assertThat(json(detail).get("questionCount").asInt()).isEqualTo(1);
        var listing=student.send("GET",base+"?size=100",null); expect(listing,200);
        var ids=new ArrayList<Long>();
        for(var item:json(listing).get("content")) { assertStudentShape(item); ids.add(item.get("id").asLong()); }
        assertThat(ids).contains(exam.id()).doesNotContain(other.id(),draft.id());
        assertThat(detail.body().contains("Original question")).isFalse();
        problem(student.send("GET",base+"?size=101",null),400);
        problem(login("teacher.a").send("GET",base,null),403);
    }

    @Test void consentUsesServerIdentityTimeAndVersionAndIsIdempotentAndBoundaryAware() throws Exception {
        var exam=published("teacher.a"); service.enroll(actor("teacher.a"),exam.id(),new EnrollmentRequest(userId("student")));
        var student=login("student"); String path="/api/v1/student/exams/"+exam.id();
        problem(student.send("POST",path+"/consent",new ConsentRequest(false,exam.consentVersion())),400);
        problem(student.send("POST",path+"/consent",Map.of("consentVersion",exam.consentVersion())),400);
        problem(student.send("POST",path+"/consent",new ConsentRequest(true,"wrong-version")),409);
        problem(login("other.student").send("POST",path+"/consent",new ConsentRequest(true,exam.consentVersion())),404);
        for(String field:List.of("studentId","acceptedAt")) {
            ObjectNode body=mapper.valueToTree(new ConsentRequest(true,exam.consentVersion())); body.put(field,"untrusted");
            problem(student.send("POST",path+"/consent",body),400);
        }
        String token=student.token; student.token=null;
        problem(student.send("POST",path+"/consent",new ConsentRequest(true,exam.consentVersion())),403);
        student.token="bad-fixture-token";
        problem(student.send("POST",path+"/consent",new ConsentRequest(true,exam.consentVersion())),403); student.token=token;
        var first=student.send("POST",path+"/consent",new ConsentRequest(true,exam.consentVersion())); expect(first,201);
        assertThat(json(first).get("acceptedAt").asText()).isEqualTo(NOW.toString());
        clock.set(NOW.plusSeconds(1)); var repeated=student.send("POST",path+"/consent",new ConsentRequest(true,exam.consentVersion())); expect(repeated,200);
        assertThat(json(first)).isEqualTo(json(repeated));
        assertThat(json(student.send("GET",path,null)).get("consentAccepted").asBoolean()).isTrue();
        clock.set(exam.opensAt()); assertThat(json(student.send("GET",path,null)).get("availability").asText()).isEqualTo("OPEN");
        clock.set(exam.closesAt().minusNanos(1000)); assertThat(json(student.send("GET",path,null)).get("availability").asText()).isEqualTo("OPEN");
        clock.set(exam.closesAt()); assertThat(json(student.send("GET",path,null)).get("availability").asText()).isEqualTo("CLOSED");
        problem(student.send("POST",path+"/consent",new ConsentRequest(true,exam.consentVersion())),409);
        var managerList=service.listEnrollments(actor("teacher.a"),exam.id(),0,20);
        assertThat(managerList.content().getFirst().consentAccepted()).isTrue();
        assertThat(managerList.content().getFirst().acceptedAt()).isEqualTo(NOW);
    }

    @Test void realHttpTeacherToStudentConsentFlow() throws Exception {
        var teacher=login("teacher.a"); var created=teacher.send("POST",BASE,metadata("HTTP end to end")); expect(created,201);
        Long id=json(created).get("id").asLong(); String path=BASE+"/"+id;
        expect(teacher.send("PUT",path+"/questions",validQuestions("HTTP secret question")),200);
        var published=teacher.send("POST",path+"/publish",null); expect(published,200);
        expect(teacher.send("POST",path+"/enrollments",new EnrollmentRequest(userId("student"))),201);
        var student=login("student"); var view=student.send("GET","/api/v1/student/exams/"+id,null); expect(view,200);
        assertStudentShape(json(view)); assertThat(view.body().contains("HTTP secret question")).isFalse();
        var consent=student.send("POST","/api/v1/student/exams/"+id+"/consent",new ConsentRequest(true,json(view).get("consentVersion").asText()));
        expect(consent,201);
        assertThat(json(student.send("GET","/api/v1/student/exams/"+id,null)).get("consentAccepted").asBoolean()).isTrue();
    }

    @Test void concurrentPublicationAndQuestionReplacementAreEquivalentToASerialOrder() throws Exception {
        var exam=prepared("teacher.a"); var teacher=actor("teacher.a");
        var results=race(()->service.publish(teacher,exam.id()),
                ()->service.replaceQuestions(teacher,exam.id(),validQuestions("Concurrent replacement")));
        assertThat(results.get(0).status()).isEqualTo(200);
        assertThat(results.get(1).status()).isIn(200,409);
        var finalExam=service.getManaged(teacher,exam.id());
        assertThat(finalExam.status()).isEqualTo(ExamStatus.PUBLISHED);
        assertThat(finalExam.publishedAt()).isEqualTo(NOW);
        if(results.get(1).status()==200) {
            // Replacement committed first; publisher must freeze that replacement.
            assertThat(finalExam.questions().getFirst().content()).isEqualTo("Concurrent replacement");
            assertThat(results.get(0).body().questions()).isEqualTo(finalExam.questions());
        } else {
            // Publication committed first; replacement must fail without changing the old content.
            assertThat(finalExam.questions()).isEqualTo(exam.questions());
        }
        assertThatThrownBy(()->service.replaceQuestions(teacher,exam.id(),validQuestions("Too late")))
                .isInstanceOf(ExamConflictException.class);
    }

    @Test void serviceRoleChecksApplyEvenWithoutAnHttpUrlGuard() {
        assertThatThrownBy(()->service.create(actor("student"),metadata("Forbidden")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->service.listStudent(actor("teacher.a"),0,20))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void concurrentEnrollmentCreatesExactlyOneRecordAndReturnsTheSameEnrollment() throws Exception {
        var exam=published("teacher.a"); var teacher=actor("teacher.a");
        var request=new EnrollmentRequest(userId("student"));
        var results=race(()->service.enroll(teacher,exam.id(),request),()->service.enroll(teacher,exam.id(),request));
        assertThat(results).allMatch(result->result.status()==200);
        assertThat(results.stream().filter(result->result.body().created()).count()).isEqualTo(1);
        assertThat(results.get(0).body().body()).isEqualTo(results.get(1).body().body());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_enrollments WHERE exam_id=? AND student_id=?",
                Integer.class,exam.id(),userId("student"))).isEqualTo(1);
    }

    @Test void concurrentConsentCreatesExactlyOneRecordAndNeverRewritesAcceptedAt() throws Exception {
        var exam=published("teacher.a"); var teacher=actor("teacher.a"); var student=actor("student");
        var enrollment=service.enroll(teacher,exam.id(),new EnrollmentRequest(student.getId())).body();
        var request=new ConsentRequest(true,exam.consentVersion());
        var results=race(()->service.acceptConsent(student,exam.id(),request),()->service.acceptConsent(student,exam.id(),request));
        assertThat(results).allMatch(result->result.status()==200);
        assertThat(results.stream().filter(result->result.body().created()).count()).isEqualTo(1);
        assertThat(results.get(0).body().body()).isEqualTo(results.get(1).body().body());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_consents WHERE enrollment_id=?",Integer.class,enrollment.id())).isEqualTo(1);
        clock.set(NOW.plusSeconds(30));
        var repeat=service.acceptConsent(student,exam.id(),request);
        assertThat(repeat.created()).isFalse(); assertThat(repeat.body().acceptedAt()).isEqualTo(NOW);
    }

    record RaceResult<T>(int status,T body) { }
    private <T> List<RaceResult<T>> race(Supplier<T> first,Supplier<T> second) throws Exception {
        var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
        var connections=new ConcurrentLinkedQueue<Long>();
        var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->raceWorker(first,ready,start,connections));
            var two=pool.submit(()->raceWorker(second,ready,start,connections));
            assertThat(ready.await(15,TimeUnit.SECONDS)).isTrue(); start.countDown();
            var results=List.of(one.get(25,TimeUnit.SECONDS),two.get(25,TimeUnit.SECONDS));
            assertThat(new HashSet<>(connections)).hasSize(2);
            return results;
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    private <T> RaceResult<T> raceWorker(Supplier<T> action,CountDownLatch ready,CountDownLatch start,
                                        ConcurrentLinkedQueue<Long> connections) {
        try {
            T body=new TransactionTemplate(transactionManager).execute(status->{
                connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                ready.countDown();
                try { if(!start.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Race barrier timed out"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return action.get();
            });
            return new RaceResult<>(200,body);
        } catch(ExamConflictException conflict) {
            // Catch only after TransactionTemplate has rolled the failed transaction back.
            return new RaceResult<>(409,null);
        }
    }

    void assertStudentShape(JsonNode body) {
        var fields=new HashSet<String>(); body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id","title","description","durationMinutes","opensAt","closesAt",
                "questionCount","serverTime","availability","consentVersion","consentText","consentAccepted");
    }

    Client login(String name) throws Exception {
        var client=new Client(); client.csrf();
        expect(client.send("POST","/api/v1/auth/login",Map.of("email",email(name),"password",PASSWORD)),200);
        client.csrf(); return client;
    }
    JsonNode json(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()); }
    void expect(HttpResponse<String> response,int status) {
        assertThat(response.statusCode()).isEqualTo(status); assertThat(response.headers().firstValue("Location").isPresent() && status!=201).isFalse();
    }
    void problem(HttpResponse<String> response,int status) throws Exception {
        expect(response,status); assertThat(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json")).isTrue();
        assertThat(json(response).get("status").asInt()).isEqualTo(status);
    }
    final class Client {
        final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String cookie,token;
        void csrf() throws Exception { var response=send("GET","/api/v1/auth/csrf",null); expect(response,200); token=json(response).get("token").asText(); }
        HttpResponse<String> send(String method,String path,Object body) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(20)).header("Accept","application/json");
            if(cookie!=null) request.header("Cookie",cookie);
            if(token!=null && !method.equals("GET")) request.header("X-CSRF-TOKEN",token);
            if(body!=null) request.header("Content-Type","application/json");
            var response=http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
            for(String header:response.headers().allValues("Set-Cookie")) if(header.startsWith("JSESSIONID=")) cookie=header.substring(0,header.indexOf(';'));
            return response;
        }
    }
    static class AdjustableClock extends Clock {
        private final AtomicReference<Instant> time=new AtomicReference<>(NOW);
        void set(Instant value) { time.set(value); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(),zone); }
        public Instant instant() { return time.get(); }
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary AdjustableClock testExamClock() { return new AdjustableClock(); }
    }
}
