package com.example.vintedbot.config;

import io.github.bonigarcia.wdm.WebDriverManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.Proxy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds throwaway headless ChromeDriver instances configured to look like a
 * real browser. A fresh driver per request keeps sessions isolated and avoids
 * state leaking between users.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebDriverFactory {

    private final VintedParserProperties props;
    private final UserAgentRotatorHolder userAgentHolder;

    /**
     * Wrapper so the rotator (a util bean) can be injected without a circular
     * package dependency in config.
     */
    @Component
    public static class UserAgentRotatorHolder {
        private final com.example.vintedbot.util.UserAgentRotator rotator;

        public UserAgentRotatorHolder(com.example.vintedbot.util.UserAgentRotator rotator) {
            this.rotator = rotator;
        }

        public String next() {
            return rotator.next();
        }
    }

    public WebDriver create() {
        if (props.getChromeDriverPath() != null && !props.getChromeDriverPath().isBlank()) {
            System.setProperty("webdriver.chrome.driver", props.getChromeDriverPath());
        } else {
            WebDriverManager.chromedriver().setup();
        }

        ChromeOptions options = new ChromeOptions();
        if (props.isHeadless()) {
            options.addArguments("--headless=new");
        }
        if (props.getChromeBinaryPath() != null && !props.getChromeBinaryPath().isBlank()) {
            options.setBinary(props.getChromeBinaryPath());
        }

        String userAgent = userAgentHolder.next();
        options.addArguments("--user-agent=" + userAgent);
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--lang=en-US");
        // Reduce automation fingerprints
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        options.setExperimentalOption("useAutomationExtension", false);

        Map<String, Object> prefs = new HashMap<>();
        prefs.put("intl.accept_languages", props.getAcceptLanguage());
        // Do NOT disable image loading (requirement); images are cached separately.
        options.setExperimentalOption("prefs", prefs);

        if (props.getProxy() != null && !props.getProxy().isBlank()) {
            Proxy proxy = new Proxy();
            proxy.setHttpProxy(props.getProxy());
            proxy.setSslProxy(props.getProxy());
            options.setProxy(proxy);
            log.debug("Chrome configured with proxy {}", props.getProxy());
        }

        ChromeDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(props.getPageLoadTimeoutSeconds()));
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));

        // Hide navigator.webdriver
        try {
            driver.executeScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");
        } catch (Exception e) {
            log.debug("Could not patch navigator.webdriver: {}", e.getMessage());
        }

        return driver;
    }
}
