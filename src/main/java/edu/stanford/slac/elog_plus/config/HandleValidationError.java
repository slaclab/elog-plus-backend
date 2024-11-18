package edu.stanford.slac.elog_plus.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class HandleValidationError {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) throws JsonProcessingException {
        StringBuilder sb = new StringBuilder();
        ex.getBindingResult().getFieldErrors().forEach(error -> sb.append("[%s] %s: %s".formatted(error.getObjectName(),error.getField(), error.getDefaultMessage())));
        Map<String, String> errors = new HashMap<>();
        errors.put("errorCode", "-1");
        errors.put("errorMessage", sb.toString());
        errors.put("errorDomain", "binding errors");
        return ResponseEntity.badRequest().body(
                errors
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public String handleNoResourceFound(NoResourceFoundException ex, Model model) {
        model.addAttribute("status", HttpStatus.NOT_FOUND.value());
        model.addAttribute("error", "Path not found");
        model.addAttribute("message", ex.getMessage());
        model.addAttribute("timestamp", System.currentTimeMillis());
        return "error"; // Custom HTML error page (error.html)
    }
}