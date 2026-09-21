package com.demo.amps.connectors.hazelcast;

import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.DataSerializableFactory;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;
import java.io.IOException;
import java.util.Objects;

/**
 * The one typed value the Hazelcast tests publish: a {@link Trade} that is an
 * {@code IdentifiedDataSerializable} under factory {@value #FACTORY_ID}, class
 * {@value #TRADE_CLASS_ID}, and the {@link Factory} a client needs before it can deliver one.
 *
 * <p>What a real application generates from a schema, hand-written here at the smallest size
 * that still proves the seam: the client only deserializes the value once the factory is
 * registered on it, and what the pipeline then receives is this object under {@code 1000/7},
 * not a rendering of it.
 */
final class Trades {

    /** The factory id, as the application would register it and configure it. */
    static final int FACTORY_ID = 1000;

    /** The class id of a {@link Trade} within the factory. */
    static final int TRADE_CLASS_ID = 7;

    private Trades() {
    }

    /** A trade: a symbol and a quantity, equal by value so a test can compare what arrived. */
    static final class Trade implements IdentifiedDataSerializable {

        private String symbol;
        private int quantity;

        /** For the factory. */
        Trade() {
        }

        Trade(String symbol, int quantity) {
            this.symbol = symbol;
            this.quantity = quantity;
        }

        /** Read by Gson, and by a test that wants the value back. */
        String symbol() {
            return symbol;
        }

        int quantity() {
            return quantity;
        }

        @Override
        public int getFactoryId() {
            return FACTORY_ID;
        }

        @Override
        public int getClassId() {
            return TRADE_CLASS_ID;
        }

        @Override
        public void writeData(ObjectDataOutput out) throws IOException {
            out.writeString(symbol);
            out.writeInt(quantity);
        }

        @Override
        public void readData(ObjectDataInput in) throws IOException {
            symbol = in.readString();
            quantity = in.readInt();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Trade trade
                    && quantity == trade.quantity
                    && Objects.equals(symbol, trade.symbol);
        }

        @Override
        public int hashCode() {
            return Objects.hash(symbol, quantity);
        }

        @Override
        public String toString() {
            return "Trade[" + symbol + " x " + quantity + "]";
        }
    }

    /** The factory a client registers under {@link #FACTORY_ID} to deserialize a {@link Trade}. */
    static final class Factory implements DataSerializableFactory {

        @Override
        public IdentifiedDataSerializable create(int classId) {
            return classId == TRADE_CLASS_ID ? new Trade() : null;
        }
    }
}
