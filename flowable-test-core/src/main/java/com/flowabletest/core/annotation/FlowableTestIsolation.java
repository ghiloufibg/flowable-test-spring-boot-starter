package com.flowabletest.core.annotation;

/**
 * Controls whether a {@link FlowableProcessTest} class may share its {@code ApplicationContext} —
 * and therefore its {@code ProcessEngine}, database, process instances, history, and jobs — with
 * other test classes through Spring's {@code TestContext} cache. Assigned to {@link
 * FlowableProcessTest#isolation()}.
 */
public enum FlowableTestIsolation {

  /**
   * No opinion -- exactly today's behavior, governed entirely by Spring's own context-caching
   * rules. Two classes with identical merged configuration may share a context, and therefore a
   * {@code ProcessEngine} and database, including whatever process instances, history, or jobs a
   * previously run class left behind.
   */
  SHARED,

  /**
   * Forces a brand-new {@code ApplicationContext} for this class alone, never shared with any other
   * class -- a genuinely separate {@code ProcessEngine} and database, built from scratch the normal
   * way an engine always starts. Pays the cost of a full context rebuild every run.
   *
   * <p>Database separation is guaranteed regardless of {@code flowable.test.datasource.provider}.
   * The {@code embedded-postgres} provider isolates naturally (a real native process or logical
   * database per context); for {@code h2}, this starter unconditionally assigns the context its own
   * uniquely-named in-memory database, overriding even a consumer-pinned fixed {@code
   * spring.datasource.url} (a common pattern, e.g. {@code jdbc:h2:mem:testdb}) -- without that
   * override, two {@code SEPARATE_CONTEXT} classes would still get distinct engines but silently
   * share the exact same physical H2 database for the JVM's lifetime.
   */
  SEPARATE_CONTEXT
}
