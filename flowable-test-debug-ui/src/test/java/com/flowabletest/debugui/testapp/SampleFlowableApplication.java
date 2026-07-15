package com.flowabletest.debugui.testapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Minimal Spring Boot app used only to bootstrap this module's own validation tests. Declares its
 * own {@link ObjectMapper} bean for the same reason {@code flowable-test-autoconfigure}'s own test
 * fixture does: this module has no {@code spring-web} on its classpath either.
 */
@SpringBootApplication
public class SampleFlowableApplication {

  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper();
  }
}
