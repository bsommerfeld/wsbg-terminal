package de.bsommerfeld.wsbg.terminal.boersefrankfurt;

import de.bsommerfeld.wsbg.terminal.core.price.OrderBookSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderBookClientTest {

    /**
     * A realistic first data frame as probed 2026-07-14: 10 levels, each row
     * carrying BOTH sides ({@code bid*} + {@code ask*}), server order
     * best-first per side.
     */
    private static final String FULL_FRAME = """
            {"isin":"DE0007164600","time":"2026-07-14T15:42:11+02:00","data":[
              {"bidOffers":3,"bidPrice":251.10,"bidUnits":420,"askOffers":2,"askPrice":251.30,"askUnits":180},
              {"bidOffers":1,"bidPrice":251.05,"bidUnits":100,"askOffers":4,"askPrice":251.35,"askUnits":600},
              {"bidOffers":2,"bidPrice":251.00,"bidUnits":350,"askOffers":1,"askPrice":251.40,"askUnits":90},
              {"bidOffers":5,"bidPrice":250.95,"bidUnits":1200,"askOffers":3,"askPrice":251.45,"askUnits":410},
              {"bidOffers":1,"bidPrice":250.90,"bidUnits":75,"askOffers":2,"askPrice":251.50,"askUnits":260},
              {"bidOffers":2,"bidPrice":250.85,"bidUnits":300,"askOffers":1,"askPrice":251.55,"askUnits":50},
              {"bidOffers":1,"bidPrice":250.80,"bidUnits":150,"askOffers":2,"askPrice":251.60,"askUnits":330},
              {"bidOffers":4,"bidPrice":250.75,"bidUnits":800,"askOffers":1,"askPrice":251.65,"askUnits":120},
              {"bidOffers":1,"bidPrice":250.70,"bidUnits":60,"askOffers":3,"askPrice":251.70,"askUnits":540},
              {"bidOffers":2,"bidPrice":250.65,"bidUnits":250,"askOffers":1,"askPrice":251.75,"askUnits":70}
            ]}""";

    @Test
    void parsesFullTenLevelFrame() {
        Optional<OrderBookSnapshot> parsed = OrderBookClient.parseFrame(FULL_FRAME);
        assertTrue(parsed.isPresent());
        OrderBookSnapshot book = parsed.get();
        assertEquals("DE0007164600", book.isin());
        assertEquals("2026-07-14T15:42:11+02:00", book.time());
        assertEquals(10, book.bids().size());
        assertEquals(10, book.asks().size());

        // Best-first: bids descending, asks ascending.
        assertEquals(251.10, book.bids().get(0).price(), 1e-9);
        assertEquals(250.65, book.bids().get(9).price(), 1e-9);
        for (int i = 1; i < book.bids().size(); i++) {
            assertTrue(book.bids().get(i - 1).price() > book.bids().get(i).price());
        }
        assertEquals(251.30, book.asks().get(0).price(), 1e-9);
        assertEquals(251.75, book.asks().get(9).price(), 1e-9);
        for (int i = 1; i < book.asks().size(); i++) {
            assertTrue(book.asks().get(i - 1).price() < book.asks().get(i).price());
        }

        // Orders + units carried through.
        assertEquals(3, book.bids().get(0).orders());
        assertEquals(420, book.bids().get(0).units());
        assertEquals(2, book.asks().get(0).orders());
        assertEquals(180, book.asks().get(0).units());
        assertEquals(5, book.bids().get(3).orders());
        assertEquals(1200, book.bids().get(3).units());
    }

    @Test
    void lopsidedFrameSkipsNullSideLevels() {
        // The ask side has fewer levels: its fields arrive null on the deeper rows.
        String frame = """
                {"isin":"DE000A0TGJ55","time":"08:03:59","data":[
                  {"bidOffers":2,"bidPrice":10.50,"bidUnits":200,"askOffers":1,"askPrice":10.60,"askUnits":100},
                  {"bidOffers":1,"bidPrice":10.45,"bidUnits":150,"askOffers":null,"askPrice":null,"askUnits":null},
                  {"bidOffers":null,"bidPrice":10.40,"bidUnits":null,"askOffers":null,"askPrice":null,"askUnits":null}
                ]}""";
        OrderBookSnapshot book = OrderBookClient.parseFrame(frame).orElseThrow();
        assertEquals(3, book.bids().size());
        assertEquals(1, book.asks().size());
        assertEquals(10.60, book.asks().get(0).price(), 1e-9);
        // Missing offers/units on a priced level default to 0, not a skip.
        assertEquals(10.40, book.bids().get(2).price(), 1e-9);
        assertEquals(0, book.bids().get(2).orders());
        assertEquals(0, book.bids().get(2).units());
    }

    @Test
    void emptyLevelArrayIsEmpty() {
        assertTrue(OrderBookClient.parseFrame("{\"isin\":\"DE0007164600\",\"data\":[]}").isEmpty());
    }

    @Test
    void brokenJsonIsEmpty() {
        assertTrue(OrderBookClient.parseFrame("{\"isin\":\"DE00071646").isEmpty());
        assertTrue(OrderBookClient.parseFrame("[1,2,3]").isEmpty());
        assertTrue(OrderBookClient.parseFrame("not json at all").isEmpty());
    }

    @Test
    void blankPayloadIsEmpty() {
        assertTrue(OrderBookClient.parseFrame("").isEmpty());
        assertTrue(OrderBookClient.parseFrame("   ").isEmpty());
        assertTrue(OrderBookClient.parseFrame(null).isEmpty());
    }

    @Test
    void isinShapeGateAnswersEmptyWithoutNetwork() {
        OrderBookClient client = new OrderBookClient();
        assertTrue(client.orderBookByIsin(null).isEmpty());
        assertTrue(client.orderBookByIsin("").isEmpty());
        assertTrue(client.orderBookByIsin("SAP").isEmpty());
        assertTrue(client.orderBookByIsin("DE00071646001").isEmpty()); // 13 chars
        assertTrue(client.orderBookByIsin("1E0007164600").isEmpty());  // digit prefix
        assertTrue(client.orderBookByIsin("DE000716460X").isEmpty());  // letter check digit
    }
}
