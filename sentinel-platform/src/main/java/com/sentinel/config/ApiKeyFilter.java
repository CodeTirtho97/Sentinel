package com.sentinel.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A static API key on {@code /api/v1/**}.
 *
 * <p>Deliberately not a full IAM. There is no user model, no org model and no token lifecycle in
 * this project, and pretending otherwise would be more code standing in for a decision nobody made.
 * What it does have to be is correct at the one thing it does.
 *
 * <p>Actuator and Swagger are outside the fence on purpose: Prometheus scrapes the first and
 * Kubernetes probes it, and the second is documentation.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";

    private static final String PROTECTED_PREFIX = "/api/v1/";

    private final byte[] expected;

    public ApiKeyFilter(String apiKey) {
        this.expected = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Everything except the API itself: Actuator, Swagger, the OpenAPI document and the static
        // demo console. Expressed as "only guard /api/v1" rather than a list of exemptions, so a
        // new endpoint under the API is protected by default rather than by remembering to add it.
        return !request.getRequestURI().startsWith(PROTECTED_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String presented = request.getHeader(HEADER);
        if (presented == null || !matches(presented)) {
            unauthorized(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Constant-time comparison.
     *
     * <p>{@code String.equals} short-circuits on the first differing byte, so the time it takes to
     * fail leaks how much of the key was right — enough to recover it a character at a time. The key
     * being static and the scope being small does not change that; this is a one-line difference and
     * exactly the detail a security-minded reviewer checks.
     */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected);
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // RFC 7807, hand-written because no handler runs for a request rejected in the filter chain.
        response.getWriter()
                .write(
                        """
                        {"type":"about:blank","title":"Unauthorized",\
                        "status":401,"detail":"a valid X-Api-Key header is required"}""");
    }
}
