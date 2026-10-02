package com.fsrs.server.controller;

import com.fsrs.server.dto.stats.HeatmapCell;
import com.fsrs.server.dto.stats.StatsSummaryResponse;
import com.fsrs.server.security.CurrentUser;
import com.fsrs.server.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/heatmap")
    public List<HeatmapCell> heatmap(@RequestParam(defaultValue = "365") int days) {
        return statsService.heatmap(CurrentUser.id(), days);
    }

    @GetMapping("/summary")
    public StatsSummaryResponse summary() {
        return statsService.summary(CurrentUser.id());
    }
}
