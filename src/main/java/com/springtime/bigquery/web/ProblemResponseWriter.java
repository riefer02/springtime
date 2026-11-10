package com.springtime.bigquery.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Writes RFC 9457 responses from servlet filters and Spring Security handlers. */
@Component
public class ProblemResponseWriter {

    private final JsonMapper jsonMapper;
    private final ApiProblemFactory problemFactory;

    public ProblemResponseWriter(JsonMapper jsonMapper, ApiProblemFactory problemFactory) {
        this.jsonMapper = jsonMapper;
        this.problemFactory = problemFactory;
    }

    public void write(HttpServletRequest request, HttpServletResponse response,
                      ErrorCode code, String detail) throws IOException {
        ProblemDetail problem = problemFactory.problem(request, code, detail);
        HttpHeaders headers = problemFactory.headers(request, code);

        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        headers.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        jsonMapper.writeValue(response.getWriter(), problem);
    }
}
