package com.referralhub.common.ids;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    @DisplayName("ids minted in sequence sort in creation order as strings")
    void idsAreLexicographicallyTimeOrdered() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ids.add(Ids.at(Instant.ofEpochMilli(1_700_000_000_000L + i)).toString());
        }
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(String::compareTo);
        assertThat(sorted).isEqualTo(ids);
    }

    @Test
    @DisplayName("the embedded timestamp round-trips")
    void timestampRoundTrips() {
        Instant when = Instant.ofEpochMilli(1_712_345_678_901L);
        assertThat(Ids.timestampOf(Ids.at(when))).isEqualTo(when.toEpochMilli());
    }

    @Test
    @DisplayName("version and variant nibbles mark the value as a valid RFC 4122 UUIDv7")
    void hasVersionSevenAndCorrectVariant() {
        UUID id = Ids.next();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    @DisplayName("ids minted in the same millisecond are still distinct")
    void distinctWithinOneMillisecond() {
        Instant fixed = Instant.ofEpochMilli(1_700_000_000_000L);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.at(fixed));
        }
        assertThat(ids).doesNotHaveDuplicates();
    }
}
