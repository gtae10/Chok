package com.project.Chok;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ChokApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChokApplication.class, args);
    }
}