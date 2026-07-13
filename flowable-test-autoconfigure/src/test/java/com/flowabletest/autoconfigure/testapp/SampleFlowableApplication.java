package com.flowabletest.autoconfigure.testapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Minimal Spring Boot app used only to bootstrap this module's own validation tests.
 *
 * <p>Declares its own {@link ObjectMapper} bean because this module has no {@code spring-web} on
 * its classpath: Spring Boot's own {@code JacksonAutoConfiguration} needs {@code
 * Jackson2ObjectMapperBuilder} (which lives in {@code spring-web}) to auto-configure one, and
 * Flowable's {@code EventRegistryKafkaConfiguration#kafkaChannelDefinitionProcessor} requires an
 * unqualified {@code ObjectMapper} bean once a Kafka Event Registry channel is deployed. A real
 * consumer app virtually always has {@code spring-web} (or another Jackson auto-configuration
 * source) already, so this gap is specific to this minimal internal fixture.
 */
@SpringBootApplication
public class SampleFlowableApplication {

  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper();
  }
}
