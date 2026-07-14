package com.flowabletest.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container annotation holding multiple {@link MockExternalService} occurrences, generated
 * implicitly through {@code @Repeatable}. Test authors should apply {@link MockExternalService}
 * directly and never reference this container.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MockExternalServices {
  MockExternalService[] value();
}
