package com.demo.amps.fix42.state.spi;

/** Which key an id is bound to: what a {@code resolveKey} hook needs to find a later message's chain. */
public interface IndexView {

    /** The OrderKey a ClOrdID is bound to, or null. */
    String keyForClOrdId(String clOrdId);

    /** The OrderKey an OrderID is bound to, or null. Never a ClOrdID's: the maps are separate (S1). */
    String keyForOrderId(String orderId);
}
