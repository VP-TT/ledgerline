package com.ledgerline.reconciliation;

import com.ledgerline.gateway.GatewayCharge;
import com.ledgerline.payment.PaymentStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static com.ledgerline.payment.PaymentStatus.*;
import static com.ledgerline.reconciliation.MismatchType.*;

/** Pure comparison of our payments against the gateway's records. No I/O, so it is tested exhaustively. */
public final class Reconciler {
  public static final Duration STUCK_AFTER = Duration.ofHours(24);

  private Reconciler() {}

  public record Ours(UUID id, PaymentStatus status, long amount, String currency, Instant createdAt) {}

  public static List<Mismatch> compare(Collection<Ours> ours, Collection<GatewayCharge> theirs, Instant asOf) {
    Map<String, Ours> byId = new HashMap<>();
    ours.forEach(o -> byId.put(o.id().toString(), o));
    Map<String, GatewayCharge> gw = new HashMap<>();
    theirs.forEach(g -> gw.put(g.paymentId(), g));

    List<Mismatch> out = new ArrayList<>();
    TreeSet<String> keys = new TreeSet<>(byId.keySet());
    keys.addAll(gw.keySet());
    for (String key : keys) {
      Ours o = byId.get(key);
      GatewayCharge g = gw.get(key);
      boolean stuck = o != null && o.status() == UNKNOWN && o.createdAt().isBefore(asOf.minus(STUCK_AFTER));

      if (g == null) {
        if (o.status() == SUCCEEDED)
          out.add(Mismatch.of(MISSING_AT_GATEWAY, o.id(), o.status().name(), null, o.amount(), null, null));
        else if (stuck)
          out.add(Mismatch.of(STUCK_UNKNOWN, o.id(), o.status().name(), null, o.amount(), null, "no gateway record"));
        continue;
      }
      if (o == null) {
        if (g.approved())
          out.add(Mismatch.of(MISSING_IN_LEDGER, parse(key), null, g.status(), null, g.amount(), "charge " + g.chargeId()));
        continue;
      }
      if (o.amount() != g.amount() || !o.currency().equals(g.currency()))
        out.add(Mismatch.of(AMOUNT_MISMATCH, o.id(), o.status().name(), g.status(), o.amount(), g.amount(),
            o.currency() + " vs " + g.currency()));
      boolean weGaveMoneyBack = o.status() == FAILED || o.status() == REVERSED;
      if ((weGaveMoneyBack && g.approved()) || (o.status() == SUCCEEDED && !g.approved()))
        out.add(Mismatch.of(STATUS_MISMATCH, o.id(), o.status().name(), g.status(), o.amount(), g.amount(), null));
      else if (stuck)
        out.add(Mismatch.of(STUCK_UNKNOWN, o.id(), o.status().name(), g.status(), o.amount(), g.amount(),
            "apply gateway outcome " + g.status()));
    }
    return out;
  }

  private static UUID parse(String id) {
    try { return UUID.fromString(id); } catch (IllegalArgumentException e) { return null; }
  }
}
