package com.flowabletest.autoconfigure.datasource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Provisions a Docker-free embedded test {@code DataSource}: H2 by default, or embedded Postgres
 * when {@code io.zonky.test:embedded-postgres} is on the classpath and selected.
 *
 * <p>H2 is the fallback -- Spring Boot's own {@code DataSourceAutoConfiguration} wires an embedded
 * H2 {@code DataSource} automatically whenever H2 is on the test classpath and no explicit {@code
 * spring.datasource.url} is configured. The nested {@code EmbeddedPostgresDataSourceConfiguration}
 * additionally activates on {@code @ConditionalOnClass(EmbeddedPostgres.class)} and, when selected,
 * replaces H2 with a Docker-free embedded Postgres instance -- for projects whose delegates rely on
 * Postgres-specific SQL (JSON columns, arrays) that H2's dialect emulation can't cover.
 *
 * <p>{@code flowable.test.datasource.provider} controls the choice ({@code auto}, the default,
 * prefers embedded Postgres whenever it's on the classpath; {@code h2} always forces H2; {@code
 * embedded-postgres} always requires it -- see {@link EmbeddedPostgresPreferredCondition}), and
 * {@code flowable.test.datasource.embedded-postgres.instance-scope} controls how the Postgres
 * provider allocates its server process ({@code per-context}, the default, forks a fresh native
 * process per Spring context; {@code shared} starts at most one process per JVM via {@link
 * EmbeddedPostgresSupport} and provisions a fresh logical database per context -- see {@link
 * EmbeddedPostgresInstanceScopeCondition}).
 *
 * <p>This class runs {@link AutoConfigureBefore before} Spring Boot's own {@code
 * DataSourceAutoConfiguration} so that its {@code @ConditionalOnMissingBean(DataSource.class)}
 * correctly backs off once one of the {@code DataSource} beans here has already been registered.
 *
 * <p>Note: this does not override {@code spring.jpa.properties.hibernate.dialect}. If the
 * consumer's test profile hardcodes an H2 dialect, switching providers means removing that override
 * too (Hibernate 6 auto-detects the dialect from the JDBC connection when none is explicitly
 * configured).
 */
@AutoConfiguration
@AutoConfigureBefore(DataSourceAutoConfiguration.class)
@ConditionalOnClass(name = "org.flowable.engine.RuntimeService")
public class FlowableTestDatasourceAutoConfiguration {

  /**
   * The {@code EmbeddedPostgres}-typed {@code @Bean} methods live in this nested class, not
   * directly on {@link FlowableTestDatasourceAutoConfiguration}, so that a consumer without {@code
   * io.zonky.test:embedded-postgres} on their classpath never fails to start.
   * {@code @ConditionalOnClass} is normally evaluated from bytecode metadata alone and never needs
   * to load the gated class -- but {@code AutowiredAnnotationBeanPostProcessor} calls the JVM's own
   * {@code Class#getDeclaredMethods()} against every configuration class to look for
   * {@code @Lookup} methods, and that raw reflection call must resolve every declared method's
   * parameter/return types up front, {@code @Conditional} or not. A method returning {@code
   * EmbeddedPostgres} directly on the outer class would throw {@code NoClassDefFoundError} from
   * that scan alone, before Spring ever gets to evaluate the condition. Nesting it sidesteps this:
   * a nested class gated by its own {@code @ConditionalOnClass} is skipped by {@code
   * ConfigurationClassParser} using ASM metadata before it is ever loaded, so its methods are never
   * reflectively enumerated either.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(EmbeddedPostgres.class)
  @ConditionalOnMissingBean(DataSource.class)
  static class EmbeddedPostgresDataSourceConfiguration {

    /**
     * {@code setRegisterShutdownHook(false)}: {@code EmbeddedPostgres} otherwise always registers
     * its own JVM shutdown hook to close the native process (zonkyio/embedded-postgres#64, #87),
     * independent of and racing this bean's own {@code destroyMethod = "close"} at test-JVM
     * shutdown -- if that hook wins the race, the native process is already dead by the time
     * Flowable's engine beans (which depend on this one and are destroyed first) try to open a JDBC
     * connection during their own {@code destroy()}. Disabling it leaves Spring's own
     * destroy-method call, which correctly runs after those dependents, as the sole owner. Guarded
     * by this class's own {@code @ConditionalOnMissingBean(DataSource.class)} so the native process
     * never forks when a consumer-supplied {@code DataSource} means it's not needed.
     */
    @Bean(destroyMethod = "close")
    @Conditional({
      EmbeddedPostgresPreferredCondition.class,
      EmbeddedPostgresInstanceScopeCondition.PerContext.class
    })
    EmbeddedPostgres embeddedPostgres() throws IOException {
      return EmbeddedPostgres.builder().setRegisterShutdownHook(false).start();
    }

    @Bean
    @Conditional({
      EmbeddedPostgresPreferredCondition.class,
      EmbeddedPostgresInstanceScopeCondition.PerContext.class
    })
    DataSource embeddedPostgresDataSource(EmbeddedPostgres embeddedPostgres) {
      return embeddedPostgres.getPostgresDatabase();
    }

    /**
     * {@code destroyMethod = "release"}: registers this context's claim on the JVM-wide shared
     * server so its shutdown hook knows to wait for this context before closing it. Guarded by this
     * class's own {@code @ConditionalOnMissingBean(DataSource.class)} so a lease is never acquired
     * (and the server never lazily started) when a consumer-supplied {@code DataSource} means
     * neither bean is actually needed.
     */
    @Bean(destroyMethod = "release")
    @Conditional({
      EmbeddedPostgresPreferredCondition.class,
      EmbeddedPostgresInstanceScopeCondition.Shared.class
    })
    EmbeddedPostgresSharedServerLease embeddedPostgresSharedServerLease() {
      return EmbeddedPostgresSupport.acquireLease();
    }

    /**
     * Takes the lease bean above as a parameter purely to establish a real Spring dependency edge
     * -- this method never uses {@code lease} beyond reaching the server it wraps -- so Spring
     * destroys the lease only after this context's Flowable engine beans, which depend on this
     * {@code DataSource} bean transitively, have already finished their own shutdown. See {@link
     * EmbeddedPostgresSupport}'s Javadoc for why that ordering, not just reference counting, is
     * what actually prevents the shared server's shutdown-hook race.
     */
    @Bean(destroyMethod = "")
    @Conditional({
      EmbeddedPostgresPreferredCondition.class,
      EmbeddedPostgresInstanceScopeCondition.Shared.class
    })
    DataSource embeddedPostgresSharedDataSource(EmbeddedPostgresSharedServerLease lease) {
      return EmbeddedPostgresSupport.freshDatabase(lease.server());
    }
  }
}
