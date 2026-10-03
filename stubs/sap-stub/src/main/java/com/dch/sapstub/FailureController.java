package com.dch.sapstub;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/admin/failures")
public class FailureController {

    private final FailureSimulator failureSimulator;

    public FailureController(FailureSimulator failureSimulator) {
        this.failureSimulator = failureSimulator;
    }

    @PostMapping
    public ResponseEntity<FailureSimulator.FailurePlan> schedule(@RequestBody FailureRequest request) {
        if (request.mode() == null || request.count() == null || request.count() < 1) {
            return ResponseEntity.badRequest().build();
        }

        long delayMs = request.delayMs() == null ? 0 : request.delayMs();
        failureSimulator.schedule(request.mode(), request.count(), Duration.ofMillis(delayMs));
        return ResponseEntity.of(failureSimulator.current());
    }

    @GetMapping
    public ResponseEntity<FailureSimulator.FailurePlan> current() {
        return ResponseEntity.of(failureSimulator.current());
    }

    @DeleteMapping
    public ResponseEntity<Void> reset() {
        failureSimulator.reset();
        return ResponseEntity.noContent().build();
    }

    public record FailureRequest(FailureMode mode, Integer count, Long delayMs) {
    }
}
