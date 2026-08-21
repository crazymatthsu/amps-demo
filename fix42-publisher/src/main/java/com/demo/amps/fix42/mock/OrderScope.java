package com.demo.amps.fix42.mock;

/**
 * Whether an order chain is a parent order or a child slice of one.
 *
 * <p>Decided by tag 9000 (ParentOrderID) on the chain's {@code 35=D}: set means
 * child, absent means parent. It selects which SOW topic family a request
 * message is routed to -- {@code sow/parent/*} or {@code sow/child/*}.
 */
public enum OrderScope {

    PARENT("parent"),
    CHILD("child");

    private final String token;

    OrderScope(String token) {
        this.token = token;
    }

    /**
     * The value substituted for <code>{scope}</code> in a configured topic
     * pattern, so one route entry covers both families.
     */
    public String token() {
        return token;
    }
}
