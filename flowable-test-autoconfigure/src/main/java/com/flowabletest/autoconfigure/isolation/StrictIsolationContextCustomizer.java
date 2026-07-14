package com.flowabletest.autoconfigure.isolation;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Forces {@code isolation = SEPARATE_CONTEXT} classes to never share a cached {@code
 * ApplicationContext} with any other test class -- including another {@code SEPARATE_CONTEXT}
 * class. A record's {@code equals}/{@code hashCode} are derived from every component, so two
 * instances are equal only when {@code testClassName} matches; since this customizer is keyed by
 * the declaring class's own (always-unique) name, no other class's cache key can ever match it,
 * which forces Spring to build a brand-new context -- and therefore, via the already-existing
 * per-context embedded-postgres/H2 default, a brand-new {@code ProcessEngine} and database -- every
 * time this class runs. Same technique {@code @DirtiesContext} uses internally, but scoped to
 * poisoning only this class's own cache key rather than also forcing a rebuild for whatever runs
 * after it.
 *
 * <p>{@code customizeContext} itself is deliberately a no-op: because the forced context is always
 * genuinely new, there is nothing left over from a previous run to clean up or verify.
 */
record StrictIsolationContextCustomizer(String testClassName) implements ContextCustomizer {

  @Override
  public void customizeContext(
      ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
    // No-op -- this customizer's sole purpose is its identity in the ContextCustomizer equals/
    // hashCode contract that Spring's context cache key relies on.
  }
}
