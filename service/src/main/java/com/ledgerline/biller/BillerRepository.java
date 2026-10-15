package com.ledgerline.biller;

import com.ledgerline.common.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class BillerRepository {
  private final JdbcClient jdbc;

  public BillerRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  public List<Biller> findActive() {
    return jdbc.sql("SELECT * FROM billers WHERE active ORDER BY name").query(Biller.class).list();
  }

  public Biller get(UUID id) {
    return jdbc.sql("SELECT * FROM billers WHERE id = :id").param("id", id).query(Biller.class).optional()
        .orElseThrow(() -> new ApiException.NotFound("Biller", id));
  }
}
