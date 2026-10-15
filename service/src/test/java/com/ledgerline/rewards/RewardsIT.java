package com.ledgerline.rewards;

import com.ledgerline.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RewardsIT extends IntegrationTest {

  UUID walletWithPoints(long expectedPoints) {
    UUID wallet = api.walletWithBalance("INR", "2000.00");
    api.pay(wallet, "1000.00", "earn-" + UUID.randomUUID());     // electricity: 2 pts / Rs 100 → 20
    settle();
    assertThat(api.points(wallet)).isEqualTo(expectedPoints);
    return wallet;
  }

  @Test
  void redeemingMovesPointsAndMoneyTogether() {
    UUID wallet = walletWithPoints(20);
    var r = api.post("/v1/rewards/redeem", "redeem-1", Map.of("walletId", wallet, "points", 8));

    assertThat(r.status()).isEqualTo(201);
    assertThat(r.body().path("credited").decimalValue()).isEqualByComparingTo("2.00");   // 8 × Rs 0.25
    assertThat(r.body().path("pointsBalance").asLong()).isEqualTo(12);
    assertThat(api.balance(wallet)).isEqualByComparingTo("1002.00");
    assertLedgerInvariants();
  }

  @Test
  void redeemIsIdempotent() {
    UUID wallet = walletWithPoints(20);
    var a = api.post("/v1/rewards/redeem", "redeem-2", Map.of("walletId", wallet, "points", 5));
    var b = api.post("/v1/rewards/redeem", "redeem-2", Map.of("walletId", wallet, "points", 5));
    assertThat(b.replayed()).isTrue();
    assertThat(b.body()).isEqualTo(a.body());
    assertThat(api.points(wallet)).isEqualTo(15);
  }

  @Test
  void cannotRedeemMorePointsThanHeld() {
    UUID wallet = walletWithPoints(20);
    var r = api.post("/v1/rewards/redeem", "redeem-3", Map.of("walletId", wallet, "points", 21));
    assertThat(r.status()).isEqualTo(422);
    assertThat(r.problemType()).isEqualTo("insufficient-points");
    assertThat(api.points(wallet)).isEqualTo(20);
    assertThat(api.balance(wallet)).isEqualByComparingTo("1000.00");
    assertLedgerInvariants();
  }

  @Test
  void smallPaymentsEarnNothingAndCatchAllRuleAppliesToOtherCategories() {
    UUID wallet = api.walletWithBalance("INR", "1000.00");
    api.pay(wallet, "99.99", "tiny");                               // rounds down to 0
    api.post("/v1/payments", "water", com.ledgerline.support.Api.payment(wallet,
        com.ledgerline.support.Api.WATER_INR, "350.00", "INR"));   // catch-all 1 pt / Rs 100 → 3
    settle();
    assertThat(api.points(wallet)).isEqualTo(3);
  }

  @Test
  void pointsAreSharedAcrossAUsersWallets() {
    String user = "user-" + UUID.randomUUID();
    UUID inr = api.createWallet(user, "INR");
    UUID usd = api.createWallet(user, "USD");
    api.topUp(usd, "50.00");
    api.post("/v1/payments", "usd-bill", com.ledgerline.support.Api.payment(usd,
        com.ledgerline.support.Api.ELECTRICITY_USD, "20.00", "USD"));  // 1 pt / $1 → 20
    settle();
    assertThat(api.points(inr)).isEqualTo(20);
    assertThat(api.points(usd)).isEqualTo(20);
  }
}
