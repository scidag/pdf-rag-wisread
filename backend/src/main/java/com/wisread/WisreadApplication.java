package com.wisread;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WisreadApplication {

    public static void main(String[] args) {
        SpringApplication.run(WisreadApplication.class, args);
    }
}
