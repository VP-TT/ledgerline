package com.ledgerline.gateway;

/** What we learned from POST /charges. Unknown means "might have been charged": never treat it as a failure. */
public sealed interface SubmitOutcome {
  record Approved(String chargeId) implements SubmitOutcome {}
  record Declined(String chargeId, String reason) implements SubmitOutcome {}
  record Unknown(String reason) implements SubmitOutcome {}
}
