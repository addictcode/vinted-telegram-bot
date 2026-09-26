package com.example.vintedbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class VintedBotApplication {

    public static void main(String[] args) {
        // The JDK disables Basic auth for HTTPS proxy tunnels by default; authenticated
        // proxies need it. Must be set before the first HttpURLConnection is created.
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        SpringApplication.run(VintedBotApplication.class, args);
    }
}
