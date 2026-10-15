package com.ledgerline.ledger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingLong;

public record EntryRequest(String type, UUID referenceId, String description, List<Posting> postings) {

  public EntryRequest {
    postings = List.copyOf(postings);
  }

  public static EntryRequest of(String type, UUID referenceId, Posting... postings) {
    return new EntryRequest(type, referenceId, null, List.of(postings));
  }

  /** Throws unless the entry has at least two non-zero legs that sum to zero in every currency. */
  public void requireBalanced() {
    if (postings.size() < 2) throw new UnbalancedEntryException(type + " needs at least two postings");
    if (postings.stream().anyMatch(p -> p.amount() == 0)) throw new UnbalancedEntryException(type + " has a zero posting");
    Map<String, Long> sums = postings.stream().collect(groupingBy(Posting::currency, summingLong(Posting::amount)));
    if (sums.values().stream().anyMatch(s -> s != 0)) throw new UnbalancedEntryException(type + " is unbalanced: " + sums);
  }
}
