package com.referralhub.search.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CursorTest {

    @Test
    @DisplayName("a cursor round-trips")
    void roundTrips() {
        Cursor cursor = new Cursor(40, 12345);

        assertThat(Cursor.decode(cursor.encode(), 12345)).isEqualTo(cursor);
    }

    @Test
    @DisplayName("a cursor from a different query is refused rather than silently paginating it")
    void refusesForeignCursors() {
        String encoded = new Cursor(20, 111).encode();

        assertThatThrownBy(() -> Cursor.decode(encoded, 222))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different query");
    }

    @Test
    @DisplayName("deep pagination is refused, not served ever more expensively")
    void refusesDeepPagination() {
        String tooDeep = new Cursor(Cursor.MAX_OFFSET + 1, 7).encode();

        assertThatThrownBy(() -> Cursor.decode(tooDeep, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    @DisplayName("garbage cursors produce a client error, not a 500")
    void rejectsMalformedCursors() {
        for (String bad : new String[] {"not-base64!!", "", "Zm9vYmFy", "MTA="}) {
            assertThatThrownBy(() -> Cursor.decode(bad, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
