package com.springtime.bigquery;

import com.springtime.bigquery.config.AppHttpProperties;
import com.springtime.bigquery.config.AppPaginationProperties;
import com.springtime.bigquery.config.AppSecurityProperties;
import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.web.ApiProblemFactory;
import com.springtime.bigquery.web.ProblemResponseWriter;
import com.springtime.bigquery.web.UpstreamErrorMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/** Supplies the properties/beans that servlet filters need inside {@code @WebMvcTest} slices. */
@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        AppHttpProperties.class,
        AppPaginationProperties.class,
        AppSecurityProperties.class,
        RetailProperties.class
})
public class ServletSliceTestConfiguration {

    @Bean
    ApiProblemFactory apiProblemFactory() {
        return new ApiProblemFactory();
    }

    @Bean
    UpstreamErrorMapper upstreamErrorMapper() {
        return new UpstreamErrorMapper();
    }

    @Bean
    ProblemResponseWriter problemResponseWriter(JsonMapper jsonMapper, ApiProblemFactory problemFactory) {
        return new ProblemResponseWriter(jsonMapper, problemFactory);
    }
}
