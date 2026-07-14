/**
 * AssertJ-style assertions over a BPMN process instance ID -- {@link
 * com.flowabletest.core.assertions.ProcessInstanceAssert}, reached via {@link
 * com.flowabletest.core.harness.ProcessTestHarness#assertThat(String)} rather than a bare static
 * factory, since evaluating an assertion needs the consumer's own engine service beans. Every
 * assertion is domain-blind (activity IDs, candidate group names, never a project-specific concept)
 * and every failure is enriched with a BPMN diagnostics snapshot of that exact process instance --
 * see {@link com.flowabletest.core.diagnostics}.
 */
package com.flowabletest.core.assertions;
