package com.moneybags.common.api;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.dao.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.*;
import org.slf4j.*;
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG=LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(BusinessException.class) public ResponseEntity<ApiError> business(BusinessException e,HttpServletRequest r) { return error(e.status(),e.code(),e.getMessage(),r,Map.of()); }
    @ExceptionHandler(MethodArgumentNotValidException.class) public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e,HttpServletRequest r) {
        Map<String,String> fields=new LinkedHashMap<>();e.getBindingResult().getFieldErrors().forEach(f->fields.putIfAbsent(f.getField(),f.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Check the highlighted fields",r,fields);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,ConstraintViolationException.class,MethodArgumentTypeMismatchException.class}) public ResponseEntity<ApiError> malformed(Exception e,HttpServletRequest r) { return error(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","Request fields or JSON are invalid",r,Map.of()); }
    @ExceptionHandler(DataIntegrityViolationException.class) public ResponseEntity<ApiError> conflict(Exception e,HttpServletRequest r) { return error(HttpStatus.CONFLICT,"DATABASE_CONSTRAINT","Operation conflicts with a database constraint or existing record",r,Map.of()); }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class) public ResponseEntity<ApiError> uploadLimit(Exception e,HttpServletRequest r) { return error(HttpStatus.PAYLOAD_TOO_LARGE,"UPLOAD_TOO_LARGE","Upload a document no larger than 5 MiB",r,Map.of()); }
    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.web.multipart.support.MissingServletRequestPartException.class}) public ResponseEntity<ApiError> missingPart(Exception e,HttpServletRequest r) { return error(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","A required request parameter or file is missing",r,Map.of()); }
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<ApiError> database(Exception e,HttpServletRequest r) { LOG.error("Database operation failed; correlationId={}; exception={}",Correlation.current(),e.getClass().getSimpleName());return error(HttpStatus.SERVICE_UNAVAILABLE,"DATABASE_UNAVAILABLE","Database operation unavailable; check the connection and installed schema",r,Map.of()); }
    @ExceptionHandler(AccessDeniedException.class) public ResponseEntity<ApiError> denied(Exception e,HttpServletRequest r) { return error(HttpStatus.FORBIDDEN,"FORBIDDEN","You do not have permission for this action",r,Map.of()); }
    @ExceptionHandler(NoResourceFoundException.class) public ResponseEntity<ApiError> notFound(Exception e,HttpServletRequest r) { return error(HttpStatus.NOT_FOUND,"NOT_FOUND","Endpoint not found",r,Map.of()); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class) public ResponseEntity<ApiError> method(Exception e,HttpServletRequest r) { return error(HttpStatus.METHOD_NOT_ALLOWED,"METHOD_NOT_ALLOWED","HTTP method is not supported for this endpoint",r,Map.of()); }
    @ExceptionHandler(Exception.class) public ResponseEntity<ApiError> unexpected(Exception e,HttpServletRequest r) { LOG.error("Unexpected error; correlationId={}; exception={}",Correlation.current(),e.getClass().getSimpleName());return error(HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR","Unexpected error; provide the correlation ID when reporting it",r,Map.of()); }
    private ResponseEntity<ApiError> error(HttpStatus s,String code,String message,HttpServletRequest r,Map<String,String> fields) { return ResponseEntity.status(s).body(new ApiError(s.value(),code,message,r.getRequestURI(),Correlation.current(),Instant.now(),fields)); }
}

