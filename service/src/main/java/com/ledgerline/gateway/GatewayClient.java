package com.ledgerline.gateway;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Talks to the payment gateway. Our payment id is the gateway's Idempotency-Key, so resubmitting a
 * payment can never create a second charge. Timeouts and 5xx come back as Unknown, not as errors.
 */
@Component
public class GatewayClient {
  private final RestClient rest;
  private final MeterRegistry metrics;

  public GatewayClient(RestClient gatewayRestClient, MeterRegistry metrics) {
    this.rest = gatewayRestClient;
    this.metrics = metrics;
  }

  public record ChargeRequest(long amount, String currency, UUID billerId, String accountRef) {}

  public SubmitOutcome submit(UUID paymentId, ChargeRequest req) {
    return timed("submit", () -> {
      try {
        return rest.post().uri("/charges")
            .header("Idempotency-Key", paymentId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(req)
            .exchange((request, response) -> {
              int status = response.getStatusCode().value();
              if (status == 200) {
                return new SubmitOutcome.Approved(response.bodyTo(GatewayCharge.class).chargeId());
              }
              if (status == 402) {
                GatewayCharge c = response.bodyTo(GatewayCharge.class);
                return new SubmitOutcome.Declined(c.chargeId(), c.declineReason());
              }
              if (status >= 500) return new SubmitOutcome.Unknown("gateway " + status);
              // 4xx other than a decline means we sent something wrong; don't guess, let the relay retry/DEAD it
              throw new IllegalStateException("gateway rejected request: " + status);
            });
      } catch (ResourceAccessException timeoutOrIo) {
        return new SubmitOutcome.Unknown(rootMessage(timeoutOrIo));
      }
    }, o -> o.getClass().getSimpleName());
  }

  public StatusOutcome status(UUID paymentId) {
    return timed("status", () -> {
      try {
        return rest.get().uri("/charges/{id}", paymentId).exchange((request, response) -> {
          int status = response.getStatusCode().value();
          if (status == 200) return new StatusOutcome.Found(response.bodyTo(GatewayCharge.class));
          if (status == 404) return new StatusOutcome.NotFound();
          return new StatusOutcome.Unknown("gateway " + status);
        });
      } catch (ResourceAccessException timeoutOrIo) {
        return new StatusOutcome.Unknown(rootMessage(timeoutOrIo));
      }
    }, o -> o.getClass().getSimpleName());
  }

  public record RefundRequest(String paymentId, long amount, String currency) {}

  /** POST /refunds, keyed by our refund id so a resubmit can't refund twice. */
  public SubmitOutcome refund(UUID refundId, RefundRequest req) {
    return timed("refund", () -> {
      try {
        return rest.post().uri("/refunds")
            .header("Idempotency-Key", refundId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(req)
            .exchange((request, response) -> {
              int status = response.getStatusCode().value();
              if (status == 200) return new SubmitOutcome.Approved(response.bodyTo(GatewayRefund.class).refundId());
              if (status == 402) {
                GatewayRefund r = response.bodyTo(GatewayRefund.class);
                return new SubmitOutcome.Declined(r.refundId(), r.declineReason());
              }
              if (status >= 500) return new SubmitOutcome.Unknown("gateway " + status);
              throw new IllegalStateException("gateway rejected refund request: " + status);
            });
      } catch (ResourceAccessException timeoutOrIo) {
        return new SubmitOutcome.Unknown(rootMessage(timeoutOrIo));
      }
    }, o -> o.getClass().getSimpleName());
  }

  public RefundLookup refundStatus(UUID refundId) {
    return timed("refund_status", () -> {
      try {
        return rest.get().uri("/refunds/{id}", refundId).exchange((request, response) -> {
          int status = response.getStatusCode().value();
          if (status == 200) return new RefundLookup.Found(response.bodyTo(GatewayRefund.class));
          if (status == 404) return new RefundLookup.NotFound();
          return new RefundLookup.Unknown("gateway " + status);
        });
      } catch (ResourceAccessException timeoutOrIo) {
        return new RefundLookup.Unknown(rootMessage(timeoutOrIo));
      }
    }, o -> o.getClass().getSimpleName());
  }

  public List<GatewayCharge> settlements(LocalDate day) {
    try {
      return rest.get().uri("/settlements?date={d}", day).retrieve()
          .body(new ParameterizedTypeReference<List<GatewayCharge>>() {});
    } catch (RestClientException e) {
      throw new IllegalStateException("could not fetch settlement report for " + day, e);
    }
  }

  private <T> T timed(String op, Supplier<T> call, java.util.function.Function<T, String> outcome) {
    long start = System.nanoTime();
    String result = "Exception";
    try {
      T t = call.get();
      result = outcome.apply(t);
      return t;
    } finally {
      Timer.builder("ledgerline.gateway.latency").tag("op", op).tag("outcome", result)
          .publishPercentileHistogram().register(metrics)
          .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
  }

  private static String rootMessage(Throwable t) {
    Throwable root = t;
    while (root.getCause() != null) root = root.getCause();
    return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
  }
}
