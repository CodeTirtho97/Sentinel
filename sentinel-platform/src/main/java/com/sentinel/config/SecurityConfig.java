package com.sentinel.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the API key filter.
 *
 * <p>A plain servlet filter rather than Spring Security: the entire requirement is one header
 * compared against one configured value, and pulling in the filter chain, an
 * {@code AuthenticationProvider} and a {@code SecurityContext} to express that would be more
 * machinery than decision.
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** The development default, and the value {@code demo.html} sends unless told otherwise. */
    public static final String DEFAULT_DEV_KEY = "local-dev-key";

    @Bean
    FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(@Value("${sentinel.api-key}") String apiKey) {
        if (DEFAULT_DEV_KEY.equals(apiKey)) {
            log.warn("sentinel.api-key is the built-in development default. "
                    + "Set SENTINEL_API_KEY before exposing this anywhere real.");
        }

        var registration = new FilterRegistrationBean<>(new ApiKeyFilter(apiKey));
        registration.addUrlPatterns("/api/v1/*");
        // Ahead of the dispatcher so a rejected request never reaches a controller.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return registration;
    }
}
