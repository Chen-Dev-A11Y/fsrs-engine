package com.fsrs.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the FSRS scheduling service.
 *
 * <p>Scheduling is enabled here rather than in a separate config class because the
 * two background jobs (Redis due-queue convergence and the daily-stat rollup) are
 * part of the service's contract, not optional extras.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.fsrs.server.mapper")
@EnableScheduling
public class FsrsServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FsrsServerApplication.class, args);
    }
}
