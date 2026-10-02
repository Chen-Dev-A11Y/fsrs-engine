package com.fsrs.server.controller;

import com.fsrs.core.FsrsConfig;
import com.fsrs.core.FsrsParams;
import com.fsrs.core.FsrsScheduler;
import com.fsrs.server.domain.SchedulerProfile;
import com.fsrs.server.dto.scheduler.SchedulerParamsRequest;
import com.fsrs.server.mapper.SchedulerProfileMapper;
import com.fsrs.server.scheduler.SchedulerFactory;
import com.fsrs.server.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scheduling parameters and the forgetting curve.
 *
 * <p>The curve endpoint exists so the frontend never re-implements the model. If the
 * browser computed {@code R(t, S)} in JavaScript, the chart and the scheduler would drift
 * apart the moment a weight changed - there would be two implementations of FSRS-6 in one
 * project. One implementation, called over HTTP.
 */
@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {

    /** Guards against a client asking for a 100-year chart. */
    private static final int MAX_CURVE_DAY = 3650;

    private final SchedulerFactory schedulerFactory;
    private final SchedulerProfileMapper profileMapper;

    public SchedulerController(SchedulerFactory schedulerFactory, SchedulerProfileMapper profileMapper) {
        this.schedulerFactory = schedulerFactory;
        this.profileMapper = profileMapper;
    }

    /** @return the weights in use plus where they came from, so the UI can label them */
    @GetMapping("/params")
    public Map<String, Object> params() {
        long userId = CurrentUser.id();
        SchedulerProfile profile = profileMapper.findByUserId(userId);
        FsrsScheduler scheduler = schedulerFactory.forUser(userId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("weights", scheduler.params().toArray());
        body.put("desiredRetention", scheduler.config().desiredRetention());
        body.put("maximumIntervalDays", scheduler.config().maximumIntervalDays());
        body.put("source", profile == null || profile.getSource() == null ? "default" : profile.getSource());
        return body;
    }

    @PutMapping("/params")
    public Map<String, Object> update(@Valid @RequestBody SchedulerParamsRequest request) {
        long userId = CurrentUser.id();
        // Validate against the model itself before persisting: an illegal weight set must
        // fail at write time, not at the next review.
        FsrsParams params = FsrsParams.of(request.weights());
        double retention = request.desiredRetention() <= 0 ? 0.9 : request.desiredRetention();
        double maximum = request.maximumIntervalDays() == null ? 36500.0 : request.maximumIntervalDays();
        new FsrsConfig(retention, maximum, false);

        SchedulerProfile profile = profileMapper.findByUserId(userId);
        long now = System.currentTimeMillis();
        if (profile == null) {
            profile = new SchedulerProfile();
            profile.setUserId(userId);
            profile.setUpdatedAt(now);
            profile.setWeights(schedulerFactory.serialize(params.toArray()));
            profile.setDesiredRetention(retention);
            profile.setMaximumIntervalDays(maximum);
            profile.setSource(request.source());
            profileMapper.insert(profile);
        } else {
            profile.setWeights(schedulerFactory.serialize(params.toArray()));
            profile.setDesiredRetention(retention);
            profile.setMaximumIntervalDays(maximum);
            profile.setSource(request.source());
            profile.setUpdatedAt(now);
            profileMapper.updateById(profile);
        }

        // Cached card states embed stability/difficulty only, so they stay valid; but the
        // new weights change every future interval, which is why nothing is cached per user.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        return body;
    }

    /**
     * Samples the forgetting curve once per day so ECharts can draw it directly.
     *
     * <p>Uses the deterministic scheduler: a chart with random jitter in it would look
     * like a bug.
     */
    @GetMapping("/curve")
    public Map<String, Object> curve(@RequestParam double stability,
                                     @RequestParam(defaultValue = "5") double difficulty,
                                     @RequestParam(defaultValue = "365") int maxDay) {
        FsrsScheduler scheduler = schedulerFactory.deterministicForUser(CurrentUser.id());
        double maxStability = Math.max(stability, FsrsScheduler.STABILITY_MIN);
        // difficulty is part of the request contract and is echoed back; with FSRS-6 the
        // curve itself depends on S only (D influences how S grows, not how R decays).
        double clampedDifficulty = Math.max(1.0, Math.min(10.0, difficulty));

        int days = Math.max(1, Math.min(maxDay, MAX_CURVE_DAY));
        List<Map<String, Object>> points = new ArrayList<>(days + 1);
        for (int day = 0; day <= days; day++) {
            points.add(Map.of("day", day, "retrievability", scheduler.retrievability(day, maxStability)));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stability", maxStability);
        body.put("difficulty", clampedDifficulty);
        body.put("decayExponent", scheduler.decayExponent());
        body.put("points", points);
        return body;
    }
}
