package com.grader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.grader.config.GraderConfig;

@SpringBootApplication
@EnableConfigurationProperties(GraderConfig.class)
public class GraderApplication {

    public static void main(String[] args) {
        SpringApplication.run(GraderApplication.class, args);
    }
}
