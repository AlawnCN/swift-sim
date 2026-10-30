package com.example.swiftsim.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler({IllegalArgumentException.class, java.time.format.DateTimeParseException.class, NumberFormatException.class})
    public ResponseEntity<Map<String, String>> bad(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
