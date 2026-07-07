package com.example.vintedbot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "telegram.bot")
public class BotProperties {

    private String token;
    private String username;
}
