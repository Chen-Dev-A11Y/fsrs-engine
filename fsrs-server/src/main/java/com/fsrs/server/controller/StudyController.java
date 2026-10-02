package com.fsrs.server.controller;

import com.fsrs.server.dto.study.ReviewRequest;
import com.fsrs.server.dto.study.ReviewResponse;
import com.fsrs.server.dto.study.StudyQueueResponse;
import com.fsrs.server.security.CurrentUser;
import com.fsrs.server.service.ReviewService;
import com.fsrs.server.service.StudyQueueService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The two hot endpoints: what to study now, and the grade that comes back. */
@RestController
@RequestMapping("/api/study")
public class StudyController {

    private final StudyQueueService queueService;
    private final ReviewService reviewService;

    public StudyController(StudyQueueService queueService, ReviewService reviewService) {
        this.queueService = queueService;
        this.reviewService = reviewService;
    }

    @GetMapping("/queue")
    public StudyQueueResponse queue(@RequestParam(required = false) Long deckId,
                                    @RequestParam(defaultValue = "20") int limit) {
        return queueService.queue(CurrentUser.id(), deckId, limit);
    }

    @PostMapping("/review")
    public ReviewResponse review(@Valid @RequestBody ReviewRequest request) {
        return reviewService.submit(CurrentUser.id(), request);
    }
}
