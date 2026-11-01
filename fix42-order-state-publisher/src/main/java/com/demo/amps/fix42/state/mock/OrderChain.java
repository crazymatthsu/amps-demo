package com.demo.amps.fix42.state.mock;

import com.demo.amps.fix42.state.fix.FixMessage;
import com.demo.amps.fix42.state.fix.FixTags;
import com.demo.amps.fix42.state.fix.Prices;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * {@code FillArithmeticTest} asserts them.
 *
 * <p>Identity follows the project contract exactly:
 * <ul>
 *   <li>tag 11 ClOrdID takes a NEW value on every D/G/F;</li>
 *   <li>tag 41 OrigClOrdID on a G/F names the ClOrdID being acted on;</li>
 *   <li>tag 37 OrderID is assigned by the venue on the first 35=8 and is then
 *       stable for the life of the chain (unless the dialect rotates it);</li>
 *   <li>tag 17 ExecID is unique per execution report;</li>
 *   <li>tag 9000 ParentOrderID, when this chain is a child, is stamped on
 *       every request the chain originates (D/G/F) and every report.</li>
 * </ul>
 *
 * <p><b>Several requests in flight</b> (this module's addition, docs/05 phase
 * 1). The chain keeps its outstanding requests as a list, oldest first, and
 * {@link #currentClOrdId()} is derived from it: the newest outstanding
 * request's id, else the working id. The single-request methods the scripted
 * flow uses are the one-element case of that list, which is why the flow
 * renders byte for byte as {@code fix42-publisher}'s ({@code ScriptedFlowPinTest}).
 * The answering methods that take an id ({@link #cancelConfirmed(String)},
 * {@link #cancelRejectFor(String, String, String)}, {@link #amendReject(String,
 * String, String)}, {@link #amendAck(String)}) name the request they answer,
 * so a test can script two cancels and answer them in either order.
 *
 * <p><b>Dialect.</b> Where venues disagree on the shape of a message, the
 * chain speaks its {@link MockDialect}; {@link MockDialect#DEFAULT} is what it
 * spoke before the switches existed.
 *
 * <p><b>Delivery faults.</b> {@link #dropLast()}, {@link #deliverLastTwoSwapped()},
 * {@link #resendLastReport()}, {@link #redeliver(int)} and friends change what
 * a drop-copy tap <i>sees</i> without changing what the venue did, so the
 * chain's own state stays the truth an applied feed must converge on.
 *
 * <p>Not thread-safe, and not meant to be: one chain is one scripted sequence.
 */
public final class OrderChain {

    private static final DateTimeFormatter TRANSACT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TRANSACT_TIME_SECONDS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss").withZone(ZoneOffset.UTC);

    /** PossDupFlag: a resent message. A session header tag; the blotter strips it. */
    private static final int POSS_DUP_FLAG = 43;

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
    private boolean market;
    private String timeInForce;

    /** Requests the venue has not answered, oldest first. */
    private final List<Request> outstanding = new ArrayList<>();
    /** Every request id -> the OrigClOrdID it named; what reports under that id carry in 41. */
    private final Map<String, String> originOf = new HashMap<>();

    private String rootClOrdId;
    /** The request id the venue last accepted. */
    private String workingClOrdId;
    private String orderId = "";
    private long cumQty;
    private long leavesQty;
    private double avgPx;

    /** The order's status as the venue holds it: what an applied feed must end on. */
    private String ordStatus = FixTags.OrdStatus.PENDING_NEW;
    /** 6 or E while the venue has acknowledged a request it has not decided. */
    private String pendingAckStatus;

    /**
     * Every fill this chain has reported, in report order. Busted entries stay
     * in the list, flagged, so fill ordinals never shift -- and so a bust or
     * correct can restate the cumulative trio by replaying the survivors from
     * zero instead of reversing rounded running totals.
     */
    private final List<Fill> fills = new ArrayList<>();

    /** Set once the unfilled balance stopped working (reject/cancel/DFD/expiry). */
    private boolean closedOut;
    private String closeStatus;

    private int clOrdSeq;
    private int execSeq;
    private int orderIdRotations;
    private Instant clock;

    private MockDialect dialect = MockDialect.DEFAULT;
    private String orderIdOverride;
    private String linkedClOrdId;
    private final Map<Integer, String> extraNewOrderTags = new LinkedHashMap<>();
    /** A D the tap did not see when it was sent; {@link #lateNewOrder()} delivers it. */
    private FixMessage heldNewOrder;

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
        this.timeInForce = timeInForce;
        this.leavesQty = orderQty;
        this.clock = start;
        this.rootClOrdId = nextClOrdId();
        this.workingClOrdId = rootClOrdId;
    }

    /**
     * A standalone parent chain, for tests that need their own order rather
     * than one of {@link MockFixFlow}'s scripted scenarios.
     */
    public static OrderChain forTest(String chainId, Instrument instrument, long orderQty,
                                     double price) {
        return forTest(chainId, instrument, orderQty, price, "0");
    }

    /** {@link #forTest(String, Instrument, long, double)} with a TimeInForce (3 IOC, 4 FOK, 6 GTD). */
    public static OrderChain forTest(String chainId, Instrument instrument, long orderQty,
                                     double price, String timeInForce) {
        return new OrderChain(chainId, OrderScope.PARENT, null, instrument, "ACC-TEST",
                "TESTER", "1", orderQty, price, timeInForce,
                java.time.Instant.parse("2026-08-21T15:00:00Z"));
    }

    // ---- set-up, before the first message -----------------------------------

    /** Speak {@code dialect} from here on. */
    public OrderChain dialect(MockDialect dialect) {
        this.dialect = dialect;
        return this;
    }

    /** The D's ClOrdID, instead of {@code <chainId>-1}. Later ids keep the {@code <chainId>-n} form. */
    public OrderChain withRootClOrdId(String clOrdId) {
        requireNoEvents("set the root ClOrdID");
        rootClOrdId = clOrdId;
        workingClOrdId = clOrdId;
        return this;
    }

    /** The OrderID the venue assigns, instead of {@code ORD-<chainId>}. */
    public OrderChain withOrderId(String orderId) {
        this.orderIdOverride = orderId;
        return this;
    }

    /** The D carries {@code 41=clOrdId}: a cancel-and-new OMS naming the order it replaces. */
    public OrderChain linkedTo(String clOrdId) {
        this.linkedClOrdId = clOrdId;
        return this;
    }

    /** The D carries {@code tag=value} as well: a venue custom tag, or a session header field. */
    public OrderChain withNewOrderTag(int tag, String value) {
        extraNewOrderTags.put(tag, value);
        return this;
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
        return record("new order", buildNewOrder());
    }

    /**
     * The D is sent, but the tap does not see it: its reports arrive first and
     * {@link #lateNewOrder()} delivers it afterwards (06 E40, E41).
     */
    public OrderChain withoutNewOrder() {
        heldNewOrder = buildNewOrder();
        return this;
    }

    /** Delivers the D that {@link #withoutNewOrder()} held back, with its original TransactTime. */
    public OrderChain lateNewOrder() {
        if (heldNewOrder == null) {
            throw new IllegalStateException("chain " + chainId + ": no held D to deliver late");
        }
        FixMessage held = heldNewOrder;
        heldNewOrder = null;
        return record("late new order", held);
    }

    /** The D again, flagged PossDup: a resend after a reconnect (06 E42). */
    public OrderChain resendNewOrder() {
        for (FixEvent event : events) {
            if (FixTags.MsgType.NEW_ORDER_SINGLE.equals(event.msgType())) {
                return record("resent new order", possDup(event.message()));
            }
        }
        throw new IllegalStateException("chain " + chainId + ": no D to resend");
    }

    private FixMessage buildNewOrder() {
        ordStatus = FixTags.OrdStatus.PENDING_NEW;
        FixMessage.Builder message = request(FixTags.MsgType.NEW_ORDER_SINGLE, rootClOrdId)
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
            message.set(FixTags.EXPIRE_TIME, format(clock.plus(Duration.ofHours(6))));
        }
        message.setIf(linkedClOrdId != null, FixTags.ORIG_CL_ORD_ID, linkedClOrdId);
        extraNewOrderTags.forEach(message::set);
        return message.build();
    }

    /**
     * 35=G OrderCancelReplaceRequest -- proposes new terms under a NEW ClOrdID,
     * naming the current id (the newest outstanding request's, else the
     * working one).
     *
     * <p>The proposed terms are staged on the request, not applied: FIX 4.2
     * says the venue decides, and until its {@code 150=5} confirms, the
     * working order is still the old one.
     */
    public OrderChain amend(long newOrderQty, double newPrice) {
        return amendNaming(currentClOrdId(), newOrderQty, newPrice);
    }

    /** {@link #amend}, naming {@code origClOrdId} in tag 41 (06 E5: G2's 41 = the working id). */
    public OrderChain amendNaming(String origClOrdId, long newOrderQty, double newPrice) {
        return amendInternal(origClOrdId, newOrderQty, newPrice, false, orderId);
    }

    /** A 35=G changing the order to market: {@code 40=1}, no 44, {@code 110=0} (06 N19). */
    public OrderChain amendToMarket(long newOrderQty) {
        return amendInternal(currentClOrdId(), newOrderQty, 0.0, true, orderId);
    }

    /** A 35=G whose tag 37 names a different order than its tag 41 does (06 E58). */
    public OrderChain amendWithOrderId(String otherOrderId, long newOrderQty, double newPrice) {
        return amendInternal(currentClOrdId(), newOrderQty, newPrice, false, otherOrderId);
    }

    private OrderChain amendInternal(String origClOrdId, long newOrderQty, double newPrice,
                                     boolean toMarket, String orderIdOnRequest) {
        String id = nextClOrdId();
        originOf.put(id, origClOrdId);
        outstanding.add(new Request(id, origClOrdId, Action.REPLACE, newOrderQty, newPrice, toMarket));
        FixMessage.Builder message = request(FixTags.MsgType.ORDER_CANCEL_REPLACE_REQUEST, id)
                .set(FixTags.ORIG_CL_ORD_ID, origClOrdId)
                .set(FixTags.HANDL_INST, "1")
                .set(FixTags.SYMBOL, instrument.symbol())
                .set(FixTags.SIDE, side)
                .set(FixTags.ORDER_QTY, newOrderQty)
                .set(FixTags.ORD_TYPE, toMarket ? "1" : "2");
        if (!toMarket) {
            message.setDecimal(FixTags.PRICE, newPrice);
        }
        message.set(FixTags.TIME_IN_FORCE, timeInForce)
                .setIf(!orderIdOnRequest.isEmpty(), FixTags.ORDER_ID, orderIdOnRequest)
                .setIf(toMarket, FixTags.MIN_QTY, "0");
        return record(toMarket ? "amend to market" : "amend request", message.build());
    }

    /** 35=F OrderCancelRequest -- asks to cancel, under a NEW ClOrdID, naming the current id. */
    public OrderChain cancelRequest() {
        return cancelRequestNaming(currentClOrdId());
    }

    /** {@link #cancelRequest()}, naming {@code origClOrdId} in tag 41 (two cancels both naming X-1). */
    public OrderChain cancelRequestNaming(String origClOrdId) {
        String id = nextClOrdId();
        originOf.put(id, origClOrdId);
        outstanding.add(new Request(id, origClOrdId, Action.CANCEL, 0, 0.0, false));
        FixMessage message = request(FixTags.MsgType.ORDER_CANCEL_REQUEST, id)
                .set(FixTags.ORIG_CL_ORD_ID, origClOrdId)
                .set(FixTags.SYMBOL, instrument.symbol())
                .set(FixTags.SIDE, side)
                .set(FixTags.ORDER_QTY, orderQty)
                .setIf(!orderId.isEmpty(), FixTags.ORDER_ID, orderId)
                .build();
        return record("cancel request", message);
    }

    // ---- venue responses ----------------------------------------------------

    /** 35=8 with 150=A/39=A -- the venue has the order and has not yet accepted it. */
    public OrderChain pendingNew() {
        return record("pending new", execution(FixTags.ExecType.PENDING_NEW,
                FixTags.OrdStatus.PENDING_NEW, currentClOrdId()).build());
    }

    /** 35=8 with 150=0/39=0 -- the venue accepts the order and assigns tag 37. */
    public OrderChain ack() {
        assignOrderId();
        ordStatus = FixTags.OrdStatus.NEW;
        return record("new ack", execution(FixTags.ExecType.NEW, FixTags.OrdStatus.NEW,
                currentClOrdId()).build());
    }

    /** 35=8 with 150=8/39=8 -- rejected before ever working. Terminal. */
    public OrderChain reject(String rejectReason, String text) {
        if (!dialect.rejectWithoutOrderId()) {
            assignOrderId();
        }
        leavesQty = 0;
        closeOut(FixTags.OrdStatus.REJECTED);
        FixMessage.Builder message = execution(FixTags.ExecType.REJECTED, FixTags.OrdStatus.REJECTED,
                currentClOrdId())
                .set(FixTags.ORD_REJ_REASON, rejectReason)
                .set(FixTags.TEXT, text);
        if (orderId.isEmpty()) {
            message.set(FixTags.ORDER_ID, "NONE");
        }
        return record("new reject", message.build());
    }

    /**
     * 35=8 with 150=1 -- a partial fill of {@code shares} at {@code lastPx}.
     *
     * <p>CumQty, LeavesQty and AvgPx are recomputed here, which is what makes
     * the resulting reports cumulative snapshots rather than increments. While
     * a request is acknowledged as pending, a partial fill keeps reporting
     * the pending status in 39 -- FIX's OrdStatus precedence.
     */
    public OrderChain partialFill(long shares, double lastPx) {
        String status = pendingAckStatus != null ? pendingAckStatus : FixTags.OrdStatus.PARTIALLY_FILLED;
        return fillInternal(shares, lastPx, FixTags.ExecType.PARTIAL_FILL, status, "partial fill");
    }

    /** 35=8 with 150=2 -- fills the entire remaining quantity. */
    public OrderChain fill(double lastPx) {
        return fillInternal(leavesQty, lastPx, FixTags.ExecType.FILL,
                FixTags.OrdStatus.FILLED, "full fill");
    }

    private OrderChain fillInternal(long shares, double lastPx, String execType,
                                    String status, String description) {
        if (closedOut) {
            throw new IllegalStateException("chain " + chainId
                    + ": cannot fill a closed-out order; use lateFill for a trade reported after it");
        }
        if (shares <= 0 || shares > leavesQty) {
            throw new IllegalArgumentException(
                    "chain " + chainId + ": cannot fill " + shares + " with " + leavesQty + " working");
        }
        avgPx = Prices.averagePrice(cumQty, avgPx, shares, lastPx);
        cumQty += shares;
        leavesQty -= shares;
        ordStatus = status;
        String clOrdId = dialect.fillsEchoPendingId() ? currentClOrdId() : workingClOrdId;
        FixMessage message = execution(execType, status, clOrdId)
                .set(FixTags.LAST_SHARES, shares)
                .setDecimal(FixTags.LAST_PX, lastPx)
                .set(FixTags.LAST_MKT, instrument.exDestination())
                .build();
        // execution() just minted this report's ExecID; remember the fill
        // under it so a later bust/correct can name it in tag 19.
        fills.add(new Fill(lastExecId(), shares, lastPx));
        return record(description, message);
    }

    /**
     * 35=8 with 150=1/2 after the order closed out -- a trade the venue matched
     * before the cancel took effect and reports after it (06 E20, docs/03 §7.12).
     *
     * <p>The report states the venue's own view of the order, as if working
     * (39=1 or 2, 151 = 38 - 14); the chain's truth stays closed out with
     * nothing working, which is what the default {@code APPLY_ECONOMICS}
     * policy leaves on the record.
     */
    public OrderChain lateFill(long shares, double lastPx) {
        if (!closedOut) {
            throw new IllegalStateException("chain " + chainId + ": a late fill needs a closed-out order");
        }
        if (shares <= 0 || cumQty + shares > orderQty) {
            throw new IllegalArgumentException("chain " + chainId + ": cannot late-fill " + shares
                    + " with " + cumQty + " of " + orderQty + " filled");
        }
        avgPx = Prices.averagePrice(cumQty, avgPx, shares, lastPx);
        cumQty += shares;
        boolean full = cumQty == orderQty;
        String status = full ? FixTags.OrdStatus.FILLED : FixTags.OrdStatus.PARTIALLY_FILLED;
        FixMessage message = execution(full ? FixTags.ExecType.FILL : FixTags.ExecType.PARTIAL_FILL,
                status, currentClOrdId())
                .set(FixTags.LEAVES_QTY, orderQty - cumQty)
                .set(FixTags.LAST_SHARES, shares)
                .setDecimal(FixTags.LAST_PX, lastPx)
                .set(FixTags.LAST_MKT, instrument.exDestination())
                .build();
        fills.add(new Fill(lastExecId(), shares, lastPx));
        return record("late fill", message);
    }

    /**
     * 35=8 with 20=1 -- the venue busts the {@code fillOrdinal}-th fill this
     * chain reported (1-based): that execution never happened.
     *
     * <p>The report restates 14/151/6/39 as absolutes recomputed over the
     * surviving fills, per the project contract. It carries no 31/32 (a bust
     * reports no new trade), and tag 150 mirrors the restated tag 39, because
     * FIX 4.2 has no bust ExecType: the semantics ride on tag 20 alone.
     *
     * <p>Busting the last fill of a FILLED order reopens it (contract edge
     * case 6). Busting after the order closed out throws unless the dialect
     * allows it ({@link MockDialect#bustAfterCloseOut()}), when the order stays
     * closed out and only 14/6 are restated (docs/03 §7.13).
     */
    public OrderChain bust(int fillOrdinal) {
        Fill target = liveFill(fillOrdinal, "bust");
        target.busted = true;
        recomputeFromFills();
        String status = restatedStatus();
        ordStatus = status;
        return record("trade bust", execution(status, status, FixTags.ExecTransType.CANCEL,
                currentClOrdId())
                .set(FixTags.EXEC_REF_ID, target.execId)
                .build());
    }

    /**
     * 35=8 with 20=2 -- the venue corrects the {@code fillOrdinal}-th fill
     * (1-based) to {@code newShares} at {@code newLastPx}.
     *
     * <p>Unlike a bust this DOES carry trade fields: the correcting report's
     * 32/31 become the execution's current values, alongside the restated
     * absolute 14/151/6/39. Same restrictions as {@link #bust(int)}.
     */
    public OrderChain correct(int fillOrdinal, long newShares, double newLastPx) {
        Fill target = liveFill(fillOrdinal, "correct");
        long restatedCum = cumQty - target.shares + newShares;
        if (newShares <= 0 || restatedCum > orderQty) {
            throw new IllegalArgumentException(
                    "chain " + chainId + ": cannot correct fill #" + fillOrdinal + " to "
                            + newShares + " shares; CumQty would be " + restatedCum
                            + " of " + orderQty);
        }
        target.shares = newShares;
        target.px = newLastPx;
        recomputeFromFills();
        String status = restatedStatus();
        ordStatus = status;
        return record("trade correct", execution(status, status, FixTags.ExecTransType.CORRECT,
                currentClOrdId())
                .set(FixTags.EXEC_REF_ID, target.execId)
                .set(FixTags.LAST_SHARES, newShares)
                .setDecimal(FixTags.LAST_PX, newLastPx)
                .set(FixTags.LAST_MKT, instrument.exDestination())
                .build());
    }

    /** {@link #amendAck(String)} for the newest outstanding amend. */
    public OrderChain amendAck() {
        return amendAck(newestOutstanding(Action.REPLACE).clOrdId);
    }

    /**
     * 35=8 with 150=5 -- the venue confirms the amend {@code clOrdId}; its terms
     * become the working terms and LeavesQty is restated against the new
     * quantity.
     *
     * <p>Note the OrdStatus: a replace confirm on a partially filled order
     * stays {@code PARTIALLY_FILLED}. The venue's tag 39 is the truth.
     *
     * <p>Under {@link MockDialect#replaceAsCancelThenNew()} the confirm is two
     * reports instead: a {@code 150=4} for the old leg and a {@code 150=0} for
     * the new one (docs/03 §7.16).
     */
    public OrderChain amendAck(String clOrdId) {
        Request amend = take(clOrdId, Action.REPLACE);
        if (FixTags.OrdStatus.PENDING_REPLACE.equals(pendingAckStatus)) {
            pendingAckStatus = null;
        }
        if (dialect.replaceAsCancelThenNew()) {
            record("replace: old leg canceled", execution(FixTags.ExecType.CANCELED,
                    FixTags.OrdStatus.CANCELED, workingClOrdId)
                    .set(FixTags.LEAVES_QTY, 0)
                    .build());
            applyTerms(amend);
            ordStatus = FixTags.OrdStatus.NEW;
            return record("replace: new leg acked", execution(FixTags.ExecType.NEW,
                    FixTags.OrdStatus.NEW, amend.clOrdId).build());
        }
        applyTerms(amend);
        ordStatus = cumQty == 0 ? FixTags.OrdStatus.NEW : FixTags.OrdStatus.PARTIALLY_FILLED;
        String reportedId = dialect.confirmsEchoOriginalId() ? amend.origClOrdId : amend.clOrdId;
        FixMessage.Builder message = execution(FixTags.ExecType.REPLACED, ordStatus, reportedId);
        if (amend.toMarket) {
            message.set(FixTags.ORD_TYPE, "1").set(FixTags.MIN_QTY, "0");
        }
        return record("amend ack", message.build());
    }

    private void applyTerms(Request amend) {
        orderQty = amend.qty;
        price = amend.toMarket ? 0.0 : amend.px;
        market = amend.toMarket;
        leavesQty = orderQty - cumQty;
        workingClOrdId = amend.clOrdId;
        if (dialect.rotateOrderIdOnReplace()) {
            orderIdRotations++;
            orderId = "ORD-" + chainId + "-R" + orderIdRotations;
        }
    }

    /** 35=8 with 150=E/39=E -- the venue acknowledges the newest amend is in flight. */
    public OrderChain pendingReplace() {
        pendingAckStatus = FixTags.OrdStatus.PENDING_REPLACE;
        ordStatus = pendingAckStatus;
        return record("pending replace", execution(FixTags.ExecType.PENDING_REPLACE,
                FixTags.OrdStatus.PENDING_REPLACE, pendingAckId(Action.REPLACE)).build());
    }

    /** 35=8 with 150=6/39=6 -- the venue acknowledges the newest cancel is in flight. */
    public OrderChain pendingCancel() {
        pendingAckStatus = FixTags.OrdStatus.PENDING_CANCEL;
        ordStatus = pendingAckStatus;
        return record("pending cancel", execution(FixTags.ExecType.PENDING_CANCEL,
                FixTags.OrdStatus.PENDING_CANCEL, pendingAckId(Action.CANCEL)).build());
    }

    private String pendingAckId(Action action) {
        if (dialect.pendingAcksEchoWorkingId()) {
            return workingClOrdId;
        }
        Request newest = newestOutstandingOrNull(action);
        return newest != null ? newest.clOrdId : currentClOrdId();
    }

    /**
     * 35=8 with 150=4/39=4 -- cancel confirmed: the newest outstanding cancel's,
     * or, with none outstanding, an unsolicited cancel (06 N13). Terminal.
     */
    public OrderChain cancelConfirmed() {
        Request cancel = newestOutstandingOrNull(Action.CANCEL);
        if (cancel != null) {
            return cancelConfirmed(cancel.clOrdId);
        }
        String clOrdId = currentClOrdId();
        leavesQty = 0;
        closeOut(FixTags.OrdStatus.CANCELED);
        return record("cancel confirmed", execution(FixTags.ExecType.CANCELED,
                FixTags.OrdStatus.CANCELED, clOrdId).build());
    }

    /**
     * 35=8 with 150=4/39=4 answering the cancel {@code clOrdId}. Other
     * outstanding requests stay outstanding: the venue answers each on its
     * own, typically by rejecting it as too late.
     */
    public OrderChain cancelConfirmed(String clOrdId) {
        Request cancel = take(clOrdId, Action.CANCEL);
        leavesQty = 0;
        closeOut(FixTags.OrdStatus.CANCELED);
        String reportedId = dialect.confirmsEchoOriginalId() ? cancel.origClOrdId : cancel.clOrdId;
        return record("cancel confirmed", execution(FixTags.ExecType.CANCELED,
                FixTags.OrdStatus.CANCELED, reportedId).build());
    }

    /** 35=8 with 150=3/39=3 -- done for day; the unfilled balance stops working. */
    public OrderChain doneForDay() {
        String clOrdId = currentClOrdId();
        leavesQty = 0;
        closeOut(FixTags.OrdStatus.DONE_FOR_DAY);
        return record("done for day", execution(FixTags.ExecType.DONE_FOR_DAY,
                FixTags.OrdStatus.DONE_FOR_DAY, clOrdId).build());
    }

    /**
     * 35=8 with 150=C/39=C -- expired: a GTD order past its ExpireTime, or a
     * day order at the close. LeavesQty goes to zero while CumQty and AvgPx
     * keep what the fills established. Terminal.
     */
    public OrderChain expire() {
        String clOrdId = currentClOrdId();
        leavesQty = 0;
        closeOut(FixTags.OrdStatus.EXPIRED);
        return record("expired", execution(FixTags.ExecType.EXPIRED,
                FixTags.OrdStatus.EXPIRED, clOrdId).build());
    }

    /**
     * 35=8 with 150=0 after the venue closed the order out by mistake: the
     * venue correcting itself by re-acknowledging the order (06 E36).
     */
    public OrderChain reack() {
        if (!closedOut) {
            throw new IllegalStateException("chain " + chainId + ": a re-ack needs a closed-out order");
        }
        closedOut = false;
        closeStatus = null;
        leavesQty = orderQty - cumQty;
        ordStatus = cumQty == 0 ? FixTags.OrdStatus.NEW : FixTags.OrdStatus.PARTIALLY_FILLED;
        return record("re-ack", execution(FixTags.ExecType.NEW, ordStatus, currentClOrdId()).build());
    }

    /**
     * 35=8 with 150=D -- the venue restates OrderQty on its own, with no
     * request from this side, giving {@code reason} in tag 378 (5 = partial
     * decline of OrderQty, the exchange-initiated partial cancel).
     *
     * <p>Refused on a closed-out order, and while an amend is in flight unless
     * the dialect allows it ({@link MockDialect#restateWhilePending()}).
     */
    public OrderChain restateOrderQty(long newOrderQty, String reason) {
        if (closedOut) {
            throw new IllegalStateException("chain " + chainId
                    + ": cannot restate a closed-out order; the stopped balance must not resurrect");
        }
        if (!dialect.restateWhilePending() && newestOutstandingOrNull(Action.REPLACE) != null) {
            throw new IllegalStateException("chain " + chainId
                    + ": cannot restate while an amend is in flight");
        }
        if (newOrderQty < cumQty) {
            throw new IllegalArgumentException("chain " + chainId + ": cannot restate OrderQty to "
                    + newOrderQty + " with " + cumQty + " already filled");
        }
        orderQty = newOrderQty;
        leavesQty = orderQty - cumQty;
        ordStatus = restatedStatus();
        return record("restated",
                execution(FixTags.ExecType.RESTATED, ordStatus, currentClOrdId())
                        .set(FixTags.EXEC_RESTATEMENT_REASON, reason)
                        .build());
    }

    /**
     * 35=8 with 20=3 -- the venue restating the order's current state
     * unprompted. Tag 17 follows {@link MockDialect#statusReportExecId()}.
     */
    public OrderChain statusReport() {
        return record("status report", statusMessage(ordStatus));
    }

    /** 35=8 with 150=I -- the answer to a 35=H OrderStatusRequest (06 N18). */
    public OrderChain statusResponse() {
        return record("status response", statusMessage("I"));
    }

    private FixMessage statusMessage(String execType) {
        FixMessage.Builder message = execution(execType, ordStatus, FixTags.ExecTransType.STATUS,
                currentClOrdId());
        String execId = dialect.statusReportExecId();
        if (execId != null) {
            message.set(FixTags.EXEC_ID, execId);
        }
        return message.build();
    }

    /** {@link #cancelRejectFor} for the newest outstanding request; {@code responseTo} sets 434. */
    public OrderChain cancelReject(String responseTo, String rejectReason, String text) {
        Request rejected = newestOutstanding(null);
        return rejectRequest(rejected,
                FixTags.MsgType.ORDER_CANCEL_REQUEST.equals(responseTo) ? "1" : "2",
                rejectReason, text);
    }

    /**
     * 35=9 OrderCancelReject -- the venue refuses the request {@code clOrdId},
     * whichever kind it is; tag 434 says which (1 a cancel, 2 a replace).
     *
     * <p>Tag 39 carries what the order is now -- or, under
     * {@link MockDialect#rejectStatusAtRequestTime()}, what it was when the
     * request arrived. A 4.2 reject may legitimately carry {@code 37=NONE}
     * when the target was never acked.
     */
    public OrderChain cancelRejectFor(String clOrdId, String rejectReason, String text) {
        Request rejected = find(clOrdId, null);
        return rejectRequest(rejected, rejected.action == Action.CANCEL ? "1" : "2", rejectReason, text);
    }

    /**
     * Rejects the amend {@code clOrdId}: a 35=9 with {@code 434=2}, or under
     * {@link MockDialect#rejectReplaceWithExecReport()} a {@code 150=8} that
     * echoes the proposed terms (docs/03 §7.15).
     */
    public OrderChain amendReject(String clOrdId, String rejectReason, String text) {
        if (dialect.rejectReplaceWithExecReport() == null) {
            return cancelRejectFor(clOrdId, rejectReason, text);
        }
        Request amend = take(clOrdId, Action.REPLACE);
        if (FixTags.OrdStatus.PENDING_REPLACE.equals(pendingAckStatus)) {
            pendingAckStatus = null;
        }
        ordStatus = workingStatus();
        FixMessage.Builder message = execution(FixTags.ExecType.REJECTED,
                dialect.rejectReplaceWithExecReport(), amend.clOrdId)
                .set(FixTags.ORDER_QTY, amend.qty)
                .set(FixTags.ORD_REJ_REASON, rejectReason)
                .set(FixTags.TEXT, text);
        if (!amend.toMarket) {
            message.setDecimal(FixTags.PRICE, amend.px);
        }
        return record("amend rejected by report", message.build());
    }

    private OrderChain rejectRequest(Request rejected, String responseTo, String rejectReason,
                                     String text) {
        outstanding.remove(rejected);
        String clearedAck = rejected.action == Action.CANCEL
                ? FixTags.OrdStatus.PENDING_CANCEL : FixTags.OrdStatus.PENDING_REPLACE;
        if (clearedAck.equals(pendingAckStatus)) {
            pendingAckStatus = null;
        }
        ordStatus = workingStatus();
        String reportedStatus = dialect.rejectStatusAtRequestTime() != null
                ? dialect.rejectStatusAtRequestTime() : ordStatus;
        FixMessage message = FixMessage.ofType(FixTags.MsgType.ORDER_CANCEL_REJECT)
                .set(FixTags.CL_ORD_ID, rejected.clOrdId)
                .set(FixTags.ORIG_CL_ORD_ID, rejected.origClOrdId)
                .set(FixTags.ORDER_ID, orderId.isEmpty() ? "NONE" : orderId)
                .set(FixTags.ORD_STATUS, reportedStatus)
                .set(FixTags.CXL_REJ_RESPONSE_TO, responseTo)
                .set(FixTags.CXL_REJ_REASON, rejectReason)
                .set(FixTags.TEXT, text)
                .set(FixTags.ACCOUNT, account)
                .setIf(scope == OrderScope.CHILD, FixTags.PARENT_ORDER_ID, parentClOrdId)
                .set(FixTags.TRANSACT_TIME, format(tick()))
                .build();
        return record("cancel reject", message);
    }

    // ---- delivery faults: what the tap sees, not what the venue did ---------

    /** The last message never reached the tap. The venue's state is unchanged. */
    public OrderChain dropLast() {
        requireEvents(1, "drop");
        events.removeLast();
        return this;
    }

    /** The tap received the last two messages in the opposite order (a tap merging two sessions). */
    public OrderChain deliverLastTwoSwapped() {
        requireEvents(2, "swap");
        FixEvent last = events.removeLast();
        events.add(events.size() - 1, last);
        return this;
    }

    /** The last execution report again, flagged PossDup (06 E30). */
    public OrderChain resendLastReport() {
        for (int i = events.size() - 1; i >= 0; i--) {
            FixEvent event = events.get(i);
            if (FixTags.MsgType.EXECUTION_REPORT.equals(event.msgType())) {
                return record("resent " + event.description(), possDup(event.message()));
            }
        }
        throw new IllegalStateException("chain " + chainId + ": no report to resend");
    }

    /** The {@code reportOrdinal}-th execution report (1-based) again: an old report redelivered (06 E22). */
    public OrderChain redeliver(int reportOrdinal) {
        FixEvent old = report(reportOrdinal);
        return record("redelivered " + old.description(), possDup(old.message()));
    }

    /**
     * The {@code reportOrdinal}-th report redelivered as a {@code 20=3} status
     * snapshot with no ExecID: an old status, outside any ExecID ring (06 E31).
     */
    public OrderChain redeliverAsStatus(int reportOrdinal) {
        FixEvent old = report(reportOrdinal);
        FixMessage message = FixMessage.ofType(FixTags.MsgType.EXECUTION_REPORT)
                .setAll(old.message())
                .set(FixTags.EXEC_TRANS_TYPE, FixTags.ExecTransType.STATUS)
                .set(FixTags.EXEC_ID, null)
                .set(FixTags.LAST_SHARES, null)
                .set(FixTags.LAST_PX, null)
                .set(FixTags.LAST_MKT, null)
                .build();
        return record("old status of " + old.description(), message);
    }

    /** The newest bust or correct again: a redelivery reaching back past it (06 E31a). */
    public OrderChain redeliverLastBust() {
        for (int i = events.size() - 1; i >= 0; i--) {
            FixMessage message = events.get(i).message();
            if (message.has(FixTags.EXEC_REF_ID)) {
                return record("redelivered " + events.get(i).description(), possDup(message));
            }
        }
        throw new IllegalStateException("chain " + chainId + ": no bust or correct to redeliver");
    }

    /** {@code count} amends, each confirmed at once: a pegged child re-priced all day (06 E61). */
    public OrderChain amendRepeatedly(int count) {
        for (int i = 0; i < count; i++) {
            amend(orderQty, price + 0.01).amendAck();
        }
        return this;
    }

    // ---- accessors ----------------------------------------------------------

    public String chainId() {
        return chainId;
    }

    public OrderScope scope() {
        return scope;
    }

    /** The D's ClOrdID. */
    public String rootClOrdId() {
        return rootClOrdId;
    }

    /** The newest outstanding request's ClOrdID, else the working one: what the next G or F names. */
    public String currentClOrdId() {
        return outstanding.isEmpty() ? workingClOrdId : outstanding.getLast().clOrdId;
    }

    /** The request id the venue last accepted. */
    public String workingClOrdId() {
        return workingClOrdId;
    }

    /** The ids of the requests the venue has not answered, oldest first. */
    public List<String> outstandingClOrdIds() {
        return outstanding.stream().map(Request::clOrdId).toList();
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

    /** The order's status as the venue holds it now. */
    public String ordStatus() {
        return ordStatus;
    }

    public boolean closedOut() {
        return closedOut;
    }

    public MockDialect dialect() {
        return dialect;
    }

    /** Every message this chain has generated, in the order the tap receives them. */
    public List<FixEvent> events() {
        return List.copyOf(events);
    }

    @Override
    public String toString() {
        return chainId;
    }

    // ---- restatement helpers ------------------------------------------------

    /** The referenced fill, after the guards every bust/correct shares. */
    private Fill liveFill(int fillOrdinal, String action) {
        if (closedOut && !dialect.bustAfterCloseOut()) {
            throw new IllegalStateException("chain " + chainId + ": cannot " + action
                    + " a fill after the order closed out; the stopped balance must not resurrect");
        }
        if (fillOrdinal < 1 || fillOrdinal > fills.size()) {
            throw new IllegalArgumentException("chain " + chainId + ": no fill #" + fillOrdinal
                    + " to " + action + " (" + fills.size() + " fills reported)");
        }
        Fill target = fills.get(fillOrdinal - 1);
        if (target.busted) {
            throw new IllegalArgumentException("chain " + chainId + ": fill #" + fillOrdinal
                    + " (" + target.execId + ") is already busted");
        }
        return target;
    }

    /**
     * Restates the cumulative trio by replaying the surviving fills from zero.
     *
     * <p>Replaying through {@link Prices#averagePrice} rather than reversing
     * the running totals matters: the running AvgPx is rounded at every step,
     * so subtraction would leak that rounding into the restated value, while a
     * replay produces exactly what a venue that never saw the busted fill
     * would have published.
     */
    private void recomputeFromFills() {
        cumQty = 0;
        avgPx = 0.0;
        for (Fill fill : fills) {
            if (!fill.busted) {
                avgPx = Prices.averagePrice(cumQty, avgPx, fill.shares, fill.px);
                cumQty += fill.shares;
            }
        }
        leavesQty = closedOut ? 0 : orderQty - cumQty;
    }

    /** Tag 39 after a restatement; on a bust, tag 150 mirrors it (4.2 has no bust type). */
    private String restatedStatus() {
        if (closedOut) {
            return closeStatus;
        }
        if (cumQty == 0) {
            return FixTags.OrdStatus.NEW;
        }
        return cumQty < orderQty ? FixTags.OrdStatus.PARTIALLY_FILLED : FixTags.OrdStatus.FILLED;
    }

    /** The order's status once no request is pending. */
    private String workingStatus() {
        if (closedOut) {
            return closeStatus;
        }
        if (cumQty == 0) {
            return FixTags.OrdStatus.NEW;
        }
        return cumQty < orderQty ? FixTags.OrdStatus.PARTIALLY_FILLED : FixTags.OrdStatus.FILLED;
    }

    private void closeOut(String status) {
        closedOut = true;
        closeStatus = status;
        ordStatus = status;
        pendingAckStatus = null;
    }

    /** One reported fill; mutable because a correct replaces 32/31 in place. */
    private static final class Fill {
        private final String execId;
        private long shares;
        private double px;
        private boolean busted;

        private Fill(String execId, long shares, double px) {
            this.execId = execId;
            this.shares = shares;
            this.px = px;
        }
    }

    private enum Action { CANCEL, REPLACE }

    /** One request the venue has not answered. */
    private record Request(String clOrdId, String origClOrdId, Action action, long qty, double px,
                           boolean toMarket) {
    }

    private Request take(String clOrdId, Action action) {
        Request request = find(clOrdId, action);
        outstanding.remove(request);
        return request;
    }

    private Request find(String clOrdId, Action action) {
        for (Request request : outstanding) {
            if (request.clOrdId.equals(clOrdId)) {
                if (action != null && request.action != action) {
                    throw new IllegalArgumentException("chain " + chainId + ": " + clOrdId + " is a "
                            + request.action + ", not a " + action);
                }
                return request;
            }
        }
        throw new IllegalArgumentException("chain " + chainId + ": no outstanding request " + clOrdId
                + " (outstanding: " + outstandingClOrdIds() + ")");
    }

    private Request newestOutstanding(Action action) {
        Request newest = newestOutstandingOrNull(action);
        if (newest == null) {
            throw new IllegalStateException("chain " + chainId + ": no outstanding "
                    + (action == null ? "request" : action));
        }
        return newest;
    }

    private Request newestOutstandingOrNull(Action action) {
        for (int i = outstanding.size() - 1; i >= 0; i--) {
            if (action == null || outstanding.get(i).action == action) {
                return outstanding.get(i);
            }
        }
        return null;
    }

    // ---- construction helpers ----------------------------------------------

    /** The fields every client-originated request carries. */
    private FixMessage.Builder request(String msgType, String clOrdId) {
        return FixMessage.ofType(msgType)
                .set(FixTags.CL_ORD_ID, clOrdId)
                .set(FixTags.ACCOUNT, account)
                .set(FixTags.CLIENT_ID, clientId)
                // Stamped on every request of a child chain, so a stateless
                // publisher can route D, G and F alike without remembering
                // which chain is whose child.
                .setIf(scope == OrderScope.CHILD, FixTags.PARENT_ORDER_ID, parentClOrdId)
                .set(FixTags.TRANSACT_TIME, format(tick()));
    }

    /**
     * The fields every execution report carries, cumulative trio included.
     * Child chains get tag 9000 here too, as a venue echoing a
     * client-supplied custom tag does.
     */
    private FixMessage.Builder execution(String execType, String status, String clOrdId) {
        return execution(execType, status, FixTags.ExecTransType.NEW, clOrdId);
    }

    private FixMessage.Builder execution(String execType, String status, String execTransType,
                                         String clOrdId) {
        execSeq++;
        // A venue's first report is not always an ack -- an IOC can be filled
        // or canceled at once -- and it carries the OrderID all the same. A
        // pending-new has none yet, and an order reject decides for itself.
        if (!FixTags.ExecType.PENDING_NEW.equals(execType)
                && !FixTags.ExecType.REJECTED.equals(execType)) {
            assignOrderId();
        }
        String origClOrdId = originOf.get(clOrdId);
        FixMessage.Builder message = FixMessage.ofType(FixTags.MsgType.EXECUTION_REPORT)
                .set(FixTags.ORDER_ID, orderId)
                .set(FixTags.CL_ORD_ID, clOrdId)
                .setIf(scope == OrderScope.CHILD, FixTags.PARENT_ORDER_ID, parentClOrdId)
                // OrigClOrdID only when the id in tag 11 is a request that
                // named a predecessor: never on the D's own id, and never
                // equal to tag 11.
                .setIf(origClOrdId != null && !origClOrdId.equals(clOrdId),
                        FixTags.ORIG_CL_ORD_ID, origClOrdId)
                .set(FixTags.EXEC_ID, lastExecIdFor(execSeq))
                .set(FixTags.EXEC_TRANS_TYPE, execTransType)
                .set(FixTags.EXEC_TYPE, execType)
                .set(FixTags.ORD_STATUS, status)
                .set(FixTags.ACCOUNT, account)
                .set(FixTags.SYMBOL, instrument.symbol())
                .set(FixTags.SIDE, side)
                .set(FixTags.ORDER_QTY, orderQty);
        if (!market) {
            message.setDecimal(FixTags.PRICE, price);
        }
        // The cumulative trio is on EVERY report, zeros included: 14=0 on an
        // ack is information, not an omitted field.
        return message
                .set(FixTags.CUM_QTY, cumQty)
                .set(FixTags.LEAVES_QTY, leavesQty)
                .setDecimal(FixTags.AVG_PX, avgPx)
                .set(FixTags.CURRENCY, instrument.currency())
                .set(FixTags.TRANSACT_TIME, format(tick()));
    }

    private String lastExecIdFor(int sequence) {
        return "EXEC-" + chainId + "-" + sequence;
    }

    private String lastExecId() {
        return lastExecIdFor(execSeq);
    }

    private void assignOrderId() {
        if (orderId.isEmpty()) {
            if (orderIdOverride != null) {
                orderId = orderIdOverride;
            } else if (dialect.orderIdEqualsClOrdId()) {
                orderId = rootClOrdId;
            } else {
                orderId = "ORD-" + chainId;
            }
        }
    }

    private FixEvent report(int reportOrdinal) {
        int seen = 0;
        for (FixEvent event : events) {
            if (FixTags.MsgType.EXECUTION_REPORT.equals(event.msgType()) && ++seen == reportOrdinal) {
                return event;
            }
        }
        throw new IllegalArgumentException("chain " + chainId + ": no report #" + reportOrdinal
                + " (" + seen + " reported)");
    }

    private static FixMessage possDup(FixMessage message) {
        return FixMessage.ofType(message.msgType()).setAll(message).set(POSS_DUP_FLAG, "Y").build();
    }

    private void requireNoEvents(String action) {
        if (!events.isEmpty() || heldNewOrder != null) {
            throw new IllegalStateException("chain " + chainId + ": cannot " + action
                    + " after the chain has started");
        }
    }

    private void requireEvents(int count, String action) {
        if (events.size() < count) {
            throw new IllegalStateException("chain " + chainId + ": nothing to " + action);
        }
    }

    private OrderChain record(String description, FixMessage message) {
        events.add(new FixEvent(chainId, scope, description, message));
        return this;
    }

    private String nextClOrdId() {
        clOrdSeq++;
        return chainId + "-" + clOrdSeq;
    }

    private String format(Instant instant) {
        return (dialect.transactTimeInSeconds() ? TRANSACT_TIME_SECONDS : TRANSACT_TIME).format(instant);
    }

    /** Deterministic clock: every message is 250ms after the last. */
    private Instant tick() {
        clock = clock.plusMillis(250);
        return clock;
    }
}
