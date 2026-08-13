package com.sentinel.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Makes the root path open the demo page.
 *
 * <p>The point is a single address worth remembering. {@code http://localhost:3000} is what gets
 * typed on a shared screen or written on a slide; {@code http://localhost:3000/demo.html} is what
 * gets mistyped.
 *
 * <p>A forward rather than a redirect, so the address bar still reads {@code localhost:3000} once
 * the page has loaded. A redirect would rewrite it to {@code /demo.html} the moment anyone looked,
 * which defeats the point of having a short URL in the first place.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/demo.html");
    }
}
