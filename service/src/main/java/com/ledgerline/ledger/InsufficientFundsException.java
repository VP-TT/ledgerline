package com.ledgerline.ledger;

import com.ledgerline.common.ApiException;

public class InsufficientFundsException extends ApiException.Unprocessable {
  public InsufficientFundsException(Account a) {
    super(a.currency().equals("PTS") ? "insufficient-points" : "insufficient-funds",
        "Insufficient " + (a.currency().equals("PTS") ? "points" : "funds") + " in " + a.code());
  }
}
