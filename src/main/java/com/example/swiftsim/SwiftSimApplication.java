package com.example.swiftsim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SwiftSimApplication {
    public static void main(String[] args) {
        SpringApplication.run(SwiftSimApplication.class, args);
    }
}
