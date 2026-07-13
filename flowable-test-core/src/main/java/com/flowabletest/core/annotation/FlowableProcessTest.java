package com.flowabletest.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.ActiveProfiles;

/**
 * One-annotation replacement for the {@code @SpringBootTest @ActiveProfiles("test")} stack a
 * Flowable BPMN test normally hand-assembles. Spring's annotation metadata resolution finds the
 * meta-annotations transitively (the same mechanism {@code @DataJpaTest} etc. rely on), so this
 * needs no extra bootstrapping machinery.
 *
 * <p>Whichever of the starter's optional capabilities are on the consumer's classpath (embedded
 * Kafka, HTTP stubbing) activate automatically via their own auto-configuration -- this annotation
 * does not need to know which ones are present.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@ActiveProfiles("test")
public @interface FlowableProcessTest {

  @AliasFor(annotation = ActiveProfiles.class, attribute = "profiles")
  String[] profiles() default "test";

  @AliasFor(annotation = SpringBootTest.class, attribute = "classes")
  Class<?>[] classes() default {};

  @AliasFor(annotation = SpringBootTest.class, attribute = "properties")
  String[] properties() default {};

  @AliasFor(annotation = SpringBootTest.class, attribute = "webEnvironment")
  SpringBootTest.WebEnvironment webEnvironment() default SpringBootTest.WebEnvironment.MOCK;
}
