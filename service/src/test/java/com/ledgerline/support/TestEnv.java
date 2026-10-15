package com.ledgerline.support;

import com.ledgerline.fakegateway.FakeGateway;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared test infrastructure, started once per JVM: a PostgreSQL database and an in-process fake gateway.
 *
 * <p>The database is a Testcontainers {@code postgres:16-alpine} by default (CI). Set
 * {@code LEDGERLINE_TEST_DB_URL} (plus {@code _USER}/{@code _PASSWORD}) to use an existing PostgreSQL
 * instead, e.g. on a machine without Docker. Either way it is real Postgres: H2 doesn't lock like it.
 */
public final class TestEnv {
  private static String url, user, password;
  private static FakeGateway gateway;

  private TestEnv() {}

  public static synchronized FakeGateway gateway() {
    if (gateway == null) {
      try {
        gateway = FakeGateway.start(0);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return gateway;
  }

  private static synchronized void database() {
    if (url != null) return;
    String env = System.getenv("LEDGERLINE_TEST_DB_URL");
    if (env != null && !env.isBlank()) {
      url = env;
      user = System.getenv().getOrDefault("LEDGERLINE_TEST_DB_USER", "ledgerline");
      password = System.getenv().getOrDefault("LEDGERLINE_TEST_DB_PASSWORD", "ledgerline");
    } else {
      var pg = new PostgreSQLContainer<>("postgres:16-alpine").withCommand("postgres", "-c", "max_connections=200");
      pg.start();
      url = pg.getJdbcUrl();
      user = pg.getUsername();
      password = pg.getPassword();
    }
  }

  /** Spring properties for a test application context. */
  public static Map<String, String> properties() {
    database();
    Map<String, String> p = new LinkedHashMap<>();
    p.put("spring.datasource.url", url);
    p.put("spring.datasource.username", user);
    p.put("spring.datasource.password", password);
    p.put("spring.datasource.hikari.maximum-pool-size", "20");
    p.put("ledgerline.scheduling.enabled", "false");            // tests drive the relay themselves
    p.put("ledgerline.gateway.base-url", gateway().baseUrl());
    p.put("ledgerline.gateway.read-timeout", "300ms");
    p.put("ledgerline.payments.status-check-base", "20ms");
    p.put("ledgerline.payments.status-check-max", "200ms");
    p.put("ledgerline.outbox.retry-base", "10ms");
    p.put("ledgerline.outbox.retry-max", "100ms");
    p.put("logging.level.com.ledgerline", "WARN");
    return p;
  }

  /** Gateway delay used by timeout modes: comfortably past the 300 ms read timeout. */
  public static final long GATEWAY_DELAY_MS = 450;
}
