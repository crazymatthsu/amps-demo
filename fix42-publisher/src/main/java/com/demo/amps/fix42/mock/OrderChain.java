package com.demo.amps.fix42.mock;

import com.demo.amps.fix42.fix.FixMessage;
import com.demo.amps.fix42.fix.FixTags;
import com.demo.amps.fix42.fix.Prices;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * A single FIX 4.2 order chain, generating its own internally consistent
 * message sequence.
 *
 * <p>This is a mock venue as much as a mock client: it holds the order's
 * economic state (working terms, CumQty, LeavesQty, AvgPx) and derives every
 * execution report from it, rather than letting a fixture author type numbers
 * that may not add up. That is deliberate -- {@code 38 = 14 + 151} on every
 * working report and an AvgPx that matches its own fills are the properties
 * that make a mock feed worth publishing, and
 * {@code OrderChainTest} asserts them.
 *
 * <p>Identity follows the project contract exactly:
 * <ul>
 *   <li>tag 11 ClOrdID takes a NEW value on every D/G/F;</li>
 *   <li>tag 41 OrigClOrdID on a G/F names the ClOrdID being acted on, which is
 *       the link the AMPS chaining key generator walks;</li>
 *   <li>tag 37 OrderID is assigned by the venue on the first 35=8 and is then
 *       stable for the life of the chain;</li>
 *   <li>tag 17 ExecID is unique per execution report;</li>
 *   <li>tag 9000 ParentOrderID, when this chain is a child, is stamped on
 *       every request the chain originates (D/G/F) so routing never needs
 *       client-side chain state.</li>
 * </ul>
 *
 * <p>Not thread-safe, and not meant to be: one chain is one scripted sequence.
 */
public final class OrderChain {

    private static final DateTimeFormatter TRANSACT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final String chainId;
    private final OrderScope scope;
    private final String parentClOrdId;
    private final Instrument instrument;
    private final String account;
    private final String clientId;
    private final String side;

    private final List<FixEvent> events = new ArrayList<>();

    /** Working terms, as last accepted by the venue. */
    private long orderQty;
    private double price;
    private String timeInForce;

    /** Terms proposed by an in-flight 35=G, applied when its 150=5 confirms. */
    private long stagedOrderQty;
    private double stagedPrice;

    private String currentClOrdId;
    private String orderId = "";
    private long cumQty;
    private long leavesQty;
    private double avgPx;

    private int clOrdSeq;
    private int execSeq;
    private Instant clock;

    OrderChain(String chainId, OrderScope scope, String parentClOrdId, Instrument instrument,
               String account, String clientId, String side, long orderQty, double price,
               String timeInForce, Instant start) {
        this.chainId = chainId;
        this.scope = scope;
        this.parentClOrdId = parentClOrdId;
        this.instrument = instrument;
        this.account = account;
        this.clientId = clientId;
        this.side = side;
        this.orderQty = orderQty;
        this.price = price;
        this.stagedOrderQty = orderQty;
        this.stagedPrice = price;
        this.timeInForce = timeInForce;
        this.leavesQty = orderQty;
        this.clock = start;
        this.currentClOrdId = nextClOrdId();
    }

    /**
     * A standalone parent chain, for tests that need their own order rather
     * than one of {@link MockFixFlow}'s scripted scenarios.
     *
     * <p>Public because the integration suite publishes a chain and stops
     * mid-amend, which the scripted flow -- deliberately made of complete
     * lifecycles -- has no scenario for.
     */
    public static OrderChain forTest(String chainId, Instrument instrument, long orderQty,
                                     double price) {
        return new OrderChain(chainId, OrderScope.PARENT, null, instrument, "ACC-TEST",
                "TESTER", "1", orderQty, price, "0", java.time.Instant.parse("2026-08-21T15:00:00Z"));
    }

    // ---- client requests ----------------------------------------------------

    /**
     * 35=D NewOrderSingle -- opens the chain.
     *
     * <p>Carries the full order terms: this is the one message published whole
     * rather than as a delta, because there is no stored record to merge into
     * yet and every later delta relies on these fields being present.
     */
    public OrderChain newOrder() {
        FixMessage.Builder message = request(FixTags.MsgType.NEW_ORDER_SINGLE)
                .set(FixTags.HANDL_INST, "1")
                .set(FixTags.SYMBOL, instrument.symbol())
                .set(FixTags.SECURITY_ID, instrument.securityId())
                .set(FixTags.SECURITY_ID_SOURCE, instrument.securityIdSource())
                .set(FixTags.SIDE, side)
                .set(FixTags.ORDER_QTY, orderQty)
                .set(FixTags.ORD_TYPE, "2")
                .setDecimal(FixTags.PRICE, price)
                .set(FixTags.TIME_IN_FORCE, timeInForce)
                .set(FixTags.CURRENCY, instrument.currency())
                .set(FixTags.EX_DESTINATION, instrument.exDestination())
                .set(FixTags.MIN_QTY, 0)
                .set(FixTags.MAX_FLOOR, orderQty / 10)
                .set(FixTags.RULE_80A, "A");
        if ("6".equals(timeInForce) || "1".equals(timeInForce)) {
            message.set(FixTags.EXPIRE_TIME, TRANSACT_TIME.format(clock.plus(Duration.ofHours(6))));
        }
        return record("new order", message.build());
    }

    /**
     * 35=G OrderCancelReplaceRequest -- proposes new terms under a NEW ClOrdID.
     *
     * <p>The proposed terms are staged, not applied: FIX 4.2 says the venue
     * decides, and until its {@code 150=5} confirms, the working order is still
     * the old one. Applying them here instead would produce a mock feed whose
     * execution reports contradict its own requests.
     */
    public OrderChain amend(long newOrderQty, double newPrice) {
        String previous = currentClOrdId;
        currentClOrdId = nextClOrdId();
        stagedOrderQty = newOrderQty;
        stagedPrice = newPrice;
        FixMessage message = request(FixTags.MsgType.ORDER_CANCEL_REPLACE_REQUEST)
                .set(FixTags.ORIG_CL_ORD_ID, previous)
                .set(FixTags.HANDL_INST, "1")
                .set(FixTags.SYMBOL, instrument.symbol())
                .set(FixTags.SIDE, side)
                .set(FixTags.ORDER_QTY, newOrderQty)
                .set(FixTags.ORD_TYPE, "2")
                .setDecimal(FixTags.PRICE, newPrice)
                .set(FixTags.TIME_IN_FORCE, timeInForce)
                .setIf(!orderId.isEmpty(), FixTags.ORDER_ID, orderId)
                .build();
        return record("amend request", message);
    }

    /** 35=F OrderCancelRequest -- asks to cancel, under a NEW ClOrdID. */
    public OrderChain cancelRequest() {
        String previous = currentClOrdId;
        currentClOrdId = nextClOrdId();
        FixMessage message = request(FixTags.MsgType.ORDER_CANCEL_REQUEST)
                .set(FixTags.ORIG_CL_ORD_ID, previous)
                .set(FixTags.SYMBOL, instrument.symbol())
                .set(FixTags.SIDE, side)
                .set(FixTags.ORDER_QTY, orderQty)
                .setIf(!orderId.isEmpty(), FixTags.ORDER_ID, orderId)
                .build();
        return record("cancel request", message);
    }

    // ---- venue responses ----------------------------------------------------

    /** 35=8 with 150=0/39=0 -- the venue accepts the order and assigns tag 37. */
    public OrderChain ack() {
        orderId = "ORD-" + chainId;
        return record("new ack", execution(FixTags.ExecType.NEW, FixTags.OrdStatus.NEW).build());
    }

    /** 35=8 with 150=8/39=8 -- rejected before ever working. Terminal. */
    public OrderChain reject(String rejectReason, String text) {
        orderId = orderId.isEmpty() ? "ORD-" + chainId : orderId;
        leavesQty = 0;
        return record("new reject", execution(FixTags.ExecType.REJECTED, FixTags.OrdStatus.REJECTED)
                .set(FixTags.ORD_REJ_REASON, rejectReason)
                .set(FixTags.TEXT, text)
                .build());
    }

    /**
     * 35=8 with 150=1 -- a partial fill of {@code shares} at {@code lastPx}.
     *
     * <p>CumQty, LeavesQty and AvgPx are recomputed here, which is what makes
     * the resulting reports cumulative snapshots rather than increments.
     */
    public OrderChain partialFill(long shares, double lastPx) {
        return fillInternal(shares, lastPx, FixTags.ExecType.PARTIAL_FILL,
                FixTags.OrdStatus.PARTIALLY_FILLED, "partial fill");
    }

    /** 35=8 with 150=2 -- fills the entire remaining quantity. Terminal. */
    public OrderChain fill(double lastPx) {
        return fillInternal(leavesQty, lastPx, FixTags.ExecType.FILL,
                FixTags.OrdStatus.FILLED, "full fill");
    }

    private OrderChain fillInternal(long shares, double lastPx, String execType,
                                    String ordStatus, String description) {
        if (shares <= 0 || shares > leavesQty) {
            throw new IllegalArgumentException(
                    "chain " + chainId + ": cannot fill " + shares + " with " + leavesQty + " working");
        }
        avgPx = Prices.averagePrice(cumQty, avgPx, shares, lastPx);
        cumQty += shares;
        leavesQty -= shares;
        return record(description, execution(execType, ordStatus)
                .set(FixTags.LAST_SHARES, shares)
                .setDecimal(FixTags.LAST_PX, lastPx)
                .set(FixTags.LAST_MKT, instrument.exDestination())
                .build());
    }

    /**
     * 35=8 with 150=5 -- the venue confirms an amend; the staged terms become
     * the working terms and LeavesQty is restated against the new quantity.
     *
     * <p>Note the OrdStatus: a replace confirm on a partially filled order
     * stays {@code PARTIALLY_FILLED}. The venue's tag 39 is the truth, and
     * forcing "replaced" here is the classic way to produce a feed that no
     * state machine can reconcile.
     */
    public OrderChain amendAck() {
        orderQty = stagedOrderQty;
        price = stagedPrice;
        leavesQty = orderQty - cumQty;
        String ordStatus = cumQty == 0 ? FixTags.OrdStatus.NEW : FixTags.OrdStatus.PARTIALLY_FILLED;
        return record("amend ack", execution(FixTags.ExecType.REPLACED, ordStatus).build());
    }

    /** 35=8 with 150=E/39=E -- the venue acknowledges the amend is in flight. */
    public OrderChain pendingReplace() {
        return record("pending replace",
                execution(FixTags.ExecType.PENDING_REPLACE, FixTags.OrdStatus.PENDING_REPLACE).build());
    }

    /** 35=8 with 150=6/39=6 -- the venue acknowledges the cancel is in flight. */
    public OrderChain pendingCancel() {
        return record("pending cancel",
                execution(FixTags.ExecType.PENDING_CANCEL, FixTags.OrdStatus.PENDING_CANCEL).build());
    }

    /** 35=8 with 150=4/39=4 -- cancel confirmed. LeavesQty goes to zero. Terminal. */
    public OrderChain cancelConfirmed() {
        leavesQty = 0;
        return record("cancel confirmed",
                execution(FixTags.ExecType.CANCELED, FixTags.OrdStatus.CANCELED).build());
    }

    /** 35=8 with 150=3/39=3 -- done for day; the unfilled balance stops working. */
    public OrderChain doneForDay() {
        leavesQty = 0;
        return record("done for day",
                execution(FixTags.ExecType.DONE_FOR_DAY, FixTags.OrdStatus.DONE_FOR_DAY).build());
    }

    /**
     * 35=9 OrderCancelReject -- the venue refuses an F or a G.
     *
     * <p>Tag 434 says which: 1 rejects a cancel, 2 rejects a replace. Tag 39
     * carries what the order still is, so the reject itself says how to revert.
     * A 4.2 reject may legitimately carry {@code 37=NONE} when the target was
     * never acked, which is why the rejects topic is keyed on tag 11.
     */
    public OrderChain cancelReject(String responseTo, String rejectReason, String text) {
        String rejected = currentClOrdId;
        // The rejected request never became the working id: the chain reverts to
        // the ClOrdID that was live before it.
        currentClOrdId = previousClOrdId();
        String ordStatus = cumQty == 0 ? FixTags.OrdStatus.NEW : FixTags.OrdStatus.PARTIALLY_FILLED;
        if (FixTags.MsgType.ORDER_CANCEL_REPLACE_REQUEST.equals(responseTo)) {
            stagedOrderQty = orderQty;
            stagedPrice = price;
        }
        FixMessage message = FixMessage.ofType(FixTags.MsgType.ORDER_CANCEL_REJECT)
                .set(FixTags.CL_ORD_ID, rejected)
                .set(FixTags.ORIG_CL_ORD_ID, currentClOrdId)
                .set(FixTags.ORDER_ID, orderId.isEmpty() ? "NONE" : orderId)
                .set(FixTags.ORD_STATUS, ordStatus)
                .set(FixTags.CXL_REJ_RESPONSE_TO,
                        FixTags.MsgType.ORDER_CANCEL_REQUEST.equals(responseTo) ? "1" : "2")
                .set(FixTags.CXL_REJ_REASON, rejectReason)
                .set(FixTags.TEXT, text)
                .set(FixTags.ACCOUNT, account)
                .setIf(scope == OrderScope.CHILD, FixTags.PARENT_ORDER_ID, parentClOrdId)
                .set(FixTags.TRANSACT_TIME, TRANSACT_TIME.format(tick()))
                .build();
        return record("cancel reject", message);
    }

    // ---- accessors ----------------------------------------------------------

    public String chainId() {
        return chainId;
    }

    public OrderScope scope() {
        return scope;
    }

    /** The ClOrdID currently working -- what the next G or F will reference. */
    public String currentClOrdId() {
        return currentClOrdId;
    }

    public String orderId() {
        return orderId;
    }

    public long cumQty() {
        return cumQty;
    }

    public long leavesQty() {
        return leavesQty;
    }

    public long orderQty() {
        return orderQty;
    }

    public double avgPx() {
        return avgPx;
    }

    /** Every message this chain has generated, in order. */
    public List<FixEvent> events() {
        return List.copyOf(events);
    }

    @Override
    public String toString() {
        return chainId;
    }

    // ---- construction helpers ----------------------------------------------

    /** The fields every client-originated request carries. */
    private FixMessage.Builder request(String msgType) {
        return FixMessage.ofType(msgType)
                .set(FixTags.CL_ORD_ID, currentClOrdId)
                .set(FixTags.ACCOUNT, account)
                .set(FixTags.CLIENT_ID, clientId)
                // Stamped on every request of a child chain, so a stateless
                // publisher can route D, G and F alike without remembering
                // which chain is whose child.
                .setIf(scope == OrderScope.CHILD, FixTags.PARENT_ORDER_ID, parentClOrdId)
                .set(FixTags.TRANSACT_TIME, TRANSACT_TIME.format(tick()));
    }

    /**
     * The fields every execution report carries, cumulative trio included.
     *
     * <p>Child chains get tag 9000 here too, not only on their requests. That
     * is a real dependency rather than a convenience: an execution report
     * resolves a pending request, so it has to reach the same blotter the
     * request went to, and a stateless router picks that topic from tag 9000.
     * Venues commonly echo a client-supplied custom tag back on execution
     * reports -- it is a standard FIX onboarding request, and exactly why
     * parent linkage is put in a custom tag. Where a venue will not, an OMS
     * has to stamp it on the way in, which is the one piece of chain state
     * this design cannot delegate to the server.
     */
    private FixMessage.Builder execution(String execType, String ordStatus) {
        execSeq++;
        return FixMessage.ofType(FixTags.MsgType.EXECUTION_REPORT)
                .set(FixTags.ORDER_ID, orderId)
                .set(FixTags.CL_ORD_ID, currentClOrdId)
                .setIf(scope == OrderScope.CHILD, FixTags.PARENT_ORDER_ID, parentClOrdId)
                // OrigClOrdID only when there genuinely is a predecessor. After
                // a rejected request the working id reverts to the previous
                // one, and emitting 41 then would set it equal to 11 -- which
                // no venue sends and which would give the chaining module a
                // self-referencing link to resolve.
                .setIf(clOrdSeq > 1 && !previousClOrdId().equals(currentClOrdId),
                        FixTags.ORIG_CL_ORD_ID, previousClOrdId())
                .set(FixTags.EXEC_ID, "EXEC-" + chainId + "-" + execSeq)
                .set(FixTags.EXEC_TRANS_TYPE, "0")
                .set(FixTags.EXEC_TYPE, execType)
                .set(FixTags.ORD_STATUS, ordStatus)
                .set(FixTags.ACCOUNT, account)
                .set(FixTags.SYMBOL, instrument.symbol())
                .set(FixTags.SIDE, side)
                .set(FixTags.ORDER_QTY, orderQty)
                .setDecimal(FixTags.PRICE, price)
                // The cumulative trio is on EVERY report, zeros included: 14=0
                // on an ack is information, not an omitted field.
                .set(FixTags.CUM_QTY, cumQty)
                .set(FixTags.LEAVES_QTY, leavesQty)
                .setDecimal(FixTags.AVG_PX, avgPx)
                .set(FixTags.CURRENCY, instrument.currency())
                .set(FixTags.TRANSACT_TIME, TRANSACT_TIME.format(tick()));
    }

    private OrderChain record(String description, FixMessage message) {
        events.add(new FixEvent(chainId, scope, description, message));
        return this;
    }

    private String nextClOrdId() {
        clOrdSeq++;
        return chainId + "-" + clOrdSeq;
    }

    private String previousClOrdId() {
        return chainId + "-" + Math.max(1, clOrdSeq - 1);
    }

    /** Deterministic clock: every message is 250ms after the last. */
    private Instant tick() {
        clock = clock.plusMillis(250);
        return clock;
    }
}
