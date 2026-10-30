package com.example.doanchuyennganh.exam;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackageClasses=ExamService.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExamExceptionHandler {
    @ExceptionHandler(InvalidExamRequestException.class)
    ProblemDetail invalid(InvalidExamRequestException e) { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,e.getMessage()); }
    @ExceptionHandler(ExamConflictException.class)
    ProblemDetail conflict(ExamConflictException e) { return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,e.getMessage()); }
    @ExceptionHandler({ConcurrencyFailureException.class,DataIntegrityViolationException.class})
    ProblemDetail concurrent(RuntimeException e) { return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,"The exam update conflicted with another operation."); }
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException e) { return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,"Access denied."); }
}
