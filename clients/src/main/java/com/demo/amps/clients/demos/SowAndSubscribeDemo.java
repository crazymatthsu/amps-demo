package com.demo.amps.clients.demos;

import com.crankuptheamps.client.Client;
import com.crankuptheamps.client.Command;
import com.crankuptheamps.client.Message;
import com.demo.amps.clients.Args;
import com.demo.amps.clients.Demo;
import com.demo.amps.clients.Messages;
import com.demo.amps.common.AmpsConnections;
import com.demo.amps.common.Console;
import com.demo.amps.common.DemoConfig;
import com.demo.amps.common.Fixtures;
import com.demo.amps.common.JsonCodec;
import com.demo.amps.common.Topics;
import com.demo.amps.market.v1.Order;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Atomic snapshot-plus-live-stream, and the OOF messages that keep it honest. */
public final class SowAndSubscribeDemo implements Demo {

    @Override
    public String name() {
        return "sow-and-subscribe";
    }

    @Override
    public String summary() {
        return "Atomic snapshot + live stream in one command, including out-of-focus messages";
    }

    @Override
    public String explanation() {
        return "The command every AMPS application is built on. AMPS sends the current "
                + "contents of the SOW matching your filter, brackets it with group_begin "
                + "and group_end, and then keeps sending updates -- with no gap between the "
                + "two and no message delivered twice. Building that yourself on a log-based "
                + "broker means reading a compacted topic to its end while buffering live "
                + "messages, and getting the seam right under load. Here it is one command. "
                + "The second half of the demo shows OOF: when a record stops matching your "
                + "filter, AMPS tells you, so a filtered view can be maintained correctly "
                + "rather than drifting.";
    }

    @Override
    public void run(Args args) throws Exception {
        String topic = args.get("topic", Topics.ORDERS);
        String filter = args.get("filter", "/status = 'ORDER_STATUS_NEW' AND /quantity > 3000");

        try (Client subscriber = AmpsConnections.connect("demo-sow-sub");
             Client publisher = AmpsConnections.connect("demo-sow-sub-pub")) {

            Console.step("sow_and_subscribe with filter: " + filter);

            AtomicInteger snapshotCount = new AtomicInteger();
            AtomicInteger liveCount = new AtomicInteger();
            AtomicInteger oofCount = new AtomicInteger();
            CountDownLatch snapshotDone = new CountDownLatch(1);
            CountDownLatch sawOof = new CountDownLatch(1);

            Command command = new Command("sow_and_subscribe")
                    .setTopic(topic)
                    .setFilter(filter)
                    // OOF asks the server to tell us when a record leaves the filter,
                    // which is the only way a filtered client-side view stays correct.
                    .setOptions(Message.Options.OOF + Message.Options.SendKeys)
                    .setBatchSize(100)
                    .setTimeout(DemoConfig.timeoutMillis());

            subscriber.executeAsync(command, message -> {
                switch (message.getCommand()) {
                    case Message.Command.GroupBegin ->
                            Console.info("   group_begin  -- snapshot starts");
                    case Message.Command.GroupEnd -> {
                        Console.info("   group_end    -- snapshot complete (%d records); "
                                + "everything after this is live", snapshotCount.get());
                        snapshotDone.countDown();
                    }
                    case Message.Command.SOW -> snapshotCount.incrementAndGet();
                    case Message.Command.OOF -> {
                        oofCount.incrementAndGet();
                        Console.info("   OOF          <- %s left the filter (reason: %s)",
                                shortKey(message), Messages.reasonName(message.getReason()));
                        sawOof.countDown();
                    }
                    default -> {
                        liveCount.incrementAndGet();
                        if (!message.isDataNull()) {
                            Order order = JsonCodec.fromJson(message.getData(),
                                    Order.getDefaultInstance());
                            Console.info("   live         <- %s qty=%d status=%s",
                                    order.getOrderId(), order.getQuantity(), order.getStatus());
                        }
                    }
                }
            });

            snapshotDone.await(15, TimeUnit.SECONDS);
            Console.kv("records in snapshot", snapshotCount.get());
            if (snapshotCount.get() == 0) {
                Console.note("Nothing matched. Run `sow-load` first, or relax --filter.");
            }

            Console.step("Publishing a new order that matches the filter");
            Order matching = Fixtures.order(9001).toBuilder()
                    .setOrderId("ORD-90001")
                    .setQuantity(4200)
                    .setStatus(com.demo.amps.market.v1.OrderStatus.ORDER_STATUS_NEW)
                    .build();
            publisher.publish(topic, JsonCodec.toJson(matching));
            publisher.publishFlush(DemoConfig.timeoutMillis());
            Thread.sleep(600);

            Console.step("Now moving that same order out of the filter");
            Console.note("The order is filled, so /status is no longer ORDER_STATUS_NEW. It "
                    + "still exists and still matches nothing about our subscription -- which "
                    + "is precisely the situation a naive client would never learn about. "
                    + "AMPS sends an OOF instead.");
            Order noLongerMatching = matching.toBuilder()
                    .setStatus(com.demo.amps.market.v1.OrderStatus.ORDER_STATUS_FILLED)
                    .setFilledQuantity(matching.getQuantity())
                    .setRevision(matching.getRevision() + 1)
                    .build();
            publisher.publish(topic, JsonCodec.toJson(noLongerMatching));
            publisher.publishFlush(DemoConfig.timeoutMillis());

            sawOof.await(10, TimeUnit.SECONDS);

            Console.step("Result");
            Console.kv("snapshot records", snapshotCount.get());
            Console.kv("live updates", liveCount.get());
            Console.kv("OOF messages", oofCount.get());
            Console.note(oofCount.get() > 0
                    ? "The OOF is the difference between a view that stays correct and one "
                      + "that accumulates rows which should have disappeared."
                    : "No OOF arrived -- check that the order actually left the filter.");
        }
    }

    private static String shortKey(Message message) {
        return message.isSowKeyNull() ? "(no sowkey)" : message.getSowKey();
    }
}
