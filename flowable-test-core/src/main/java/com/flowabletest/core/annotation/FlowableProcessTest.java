package com.flowabletest.core.annotation;

import com.flowabletest.core.diagnostics.FlowableProcessDiagnosticsExtension;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
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
@ExtendWith(FlowableProcessDiagnosticsExtension.class)
public @interface FlowableProcessTest {

  @AliasFor(annotation = ActiveProfiles.class, attribute = "profiles")
  String[] profiles() default "test";

  @AliasFor(annotation = SpringBootTest.class, attribute = "classes")
  Class<?>[] classes() default {};

  @AliasFor(annotation = SpringBootTest.class, attribute = "properties")
  String[] properties() default {};

  @AliasFor(annotation = SpringBootTest.class, attribute = "webEnvironment")
  SpringBootTest.WebEnvironment webEnvironment() default SpringBootTest.WebEnvironment.MOCK;

  /**
   * {@link FlowableTestIsolation#SHARED} (the default) participates in Spring's ordinary
   * context-caching exactly as before this attribute existed. {@link
   * FlowableTestIsolation#SEPARATE_CONTEXT} guarantees this class never shares its {@code
   * ApplicationContext} -- and therefore its {@code ProcessEngine} and database -- with any other
   * test class; see that constant's Javadoc for how database separation is achieved.
   */
  FlowableTestIsolation isolation() default FlowableTestIsolation.SHARED;

  /**
   * Additional BPMN processes to deploy for this class specifically, on top of whatever {@code
   * flowable.test.processes.deploy} already deploys by default. Each name resolves to {@code
   * <flowable.test.processes.root>/<name>.bpmn20.xml} -- the same one-file-per-process convention
   * used everywhere else in this starter. Each name is the BPMN <b>file</b> name (e.g. {@code
   * "order-processing"}), not the {@code <process id="...">} declared inside it (e.g. {@code
   * "orderProcessing"}) -- the two commonly differ by hyphenation.
   */
  String[] processes() default {};
}
