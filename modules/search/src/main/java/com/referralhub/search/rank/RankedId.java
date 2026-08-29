package com.referralhub.search.rank;

import java.util.UUID;

/** One document's position in one retriever's result list. */
public record RankedId(UUID id, int rank, double retrieverScore) {
}
