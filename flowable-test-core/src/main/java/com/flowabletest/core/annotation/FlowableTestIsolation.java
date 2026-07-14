package com.flowabletest.core.annotation;

/**
 * Controls whether a {@link FlowableProcessTest} class may share its {@code ApplicationContext}
 * (and therefore its {@code ProcessEngine}, database, process instances, history, and jobs) with
 * other test classes via Spring's {@code TestContext} cache.
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
   */
  SEPARATE_CONTEXT
}
