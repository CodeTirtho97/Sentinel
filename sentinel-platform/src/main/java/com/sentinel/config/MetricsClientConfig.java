package com.sentinel.config;

import com.sentinel.slo.ShardAssignment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class MetricsClientConfig {

    @Bean
    public RestClient prometheusRestClient(SentinelProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getMetrics().getTimeout());
        factory.setReadTimeout(props.getMetrics().getTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean
    public ShardAssignment shardAssignment(SentinelProperties props) {
        return new ShardAssignment(
                props.getEvaluation().getShardIndex(), props.getEvaluation().getShardCount());
    }
}
