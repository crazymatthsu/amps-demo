package com.demo.amps.connectors.codec;

import com.demo.amps.connectors.decode.RecordDecoder;
import com.demo.amps.connectors.encode.PayloadEncoder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one non-text codec the core tests prove the seam with: a stand-in for a generated
 * message class -- a mutable {@link Order} with scalar fields, one nested {@link Party} and
 * one repeated field -- whose decoder returns a lazy {@link FieldView} and whose encoder
 * writes JSON bytes.
 *
 * <p>What a real protobuf or Thrift codec would do with descriptors, this does by hand, and
 * it <em>counts</em>: every field read through a view is tallied by name, and every
 * {@link FieldView#copy()} is tallied too, so a test can assert that a rule which names one
 * field read one field, that the encoder wrote the mutated builder without reading it back
 * through the map, and that a step copied rather than mutated. The decoder accepts the
 * object itself (what a Hazelcast map would deliver) or its JSON bytes (what a Kafka topic
 * would); the encoder accepts a view of its own type -- and writes {@link FieldView#target()}
 * straight out -- or a plain map, which it rebuilds an {@link Order} from first.
 */
public final class TestPojoCodec implements PayloadCodec {

    /** Factory 100, class 1: the type this codec is registered under. */
    public static final PayloadType TYPE = PayloadType.of(100, 1);

    /** The schema, in wire order. */
    private static final List<String> FIELDS =
            List.of("id", "qty", "price", "status", "party", "legs");

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final Map<String, AtomicInteger> reads = new ConcurrentHashMap<>();
    private final AtomicInteger copies = new AtomicInteger();

    @Override
    public PayloadType type() {
        return TYPE;
    }

    @Override
    public RecordDecoder decoder() {
        return payload -> new OrderView(toOrder(payload));
    }

    @Override
    public PayloadEncoder encoder() {
        return fields -> fields instanceof OrderView view
                ? json(view.order)
                : json(Order.fromMap(fields));
    }

    /** How many times {@code field} was read through a view since the last {@link #reset()}. */
    public int reads(String field) {
        AtomicInteger count = reads.get(field);
        return count == null ? 0 : count.get();
    }

    /** Every field read through a view, with its count, since the last {@link #reset()}. */
    public Map<String, Integer> readsByField() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        for (String field : FIELDS) {
            if (reads.containsKey(field)) {
                snapshot.put(field, reads.get(field).get());
            }
        }
        reads.forEach((field, count) -> snapshot.putIfAbsent(field, count.get()));
        return snapshot;
    }

    /** How many views were copied since the last {@link #reset()}. */
    public int copies() {
        return copies.get();
    }

    /** Forget the counters. */
    public void reset() {
        reads.clear();
        copies.set(0);
    }

    private void read(String field) {
        reads.computeIfAbsent(field, name -> new AtomicInteger()).incrementAndGet();
    }

    private Order toOrder(Object payload) {
        if (payload instanceof Order order) {
            return order;
        }
        if (payload instanceof String || payload instanceof byte[]) {
            try {
                Map<?, ?> parsed = mapper.readValue(Payloads.text(payload), Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> fields = (Map<String, Object>) parsed;
                return Order.fromMap(fields);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("not a JSON order: " + e.getOriginalMessage(), e);
            }
        }
        throw new IllegalArgumentException("payload type " + TYPE + " cannot decode a "
                + (payload == null ? "null" : payload.getClass().getName()));
    }

    private byte[] json(Order order) {
        try {
            return mapper.writeValueAsBytes(order.toMap());
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The "generated" message: mutable, like a builder, with a deep {@link #copy()}. */
    public static final class Order {

        private String id;
        private Long qty;
        private BigDecimal price;
        private String status;
        private Party party;
        private final List<String> legs = new ArrayList<>();

        public Order() {
        }

        public Order(String id, long qty, String price) {
            this.id = id;
            this.qty = qty;
            this.price = new BigDecimal(price);
        }

        public String id() {
            return id;
        }

        public Long qty() {
            return qty;
        }

        public BigDecimal price() {
            return price;
        }

        public String status() {
            return status;
        }

        public Party party() {
            return party;
        }

        public List<String> legs() {
            return Collections.unmodifiableList(legs);
        }

        public Order id(String id) {
            this.id = id;
            return this;
        }

        public Order qty(Long qty) {
            this.qty = qty;
            return this;
        }

        public Order price(BigDecimal price) {
            this.price = price;
            return this;
        }

        public Order status(String status) {
            this.status = status;
            return this;
        }

        public Order party(Party party) {
            this.party = party;
            return this;
        }

        public Order legs(List<String> legs) {
            this.legs.clear();
            if (legs != null) {
                this.legs.addAll(legs);
            }
            return this;
        }

        public Order addLeg(String leg) {
            legs.add(leg);
            return this;
        }

        /** A deep copy: the nested party and the legs are copied too. */
        public Order copy() {
            Order copy = new Order();
            copy.id = id;
            copy.qty = qty;
            copy.price = price;
            copy.status = status;
            copy.party = party == null ? null : party.copy();
            copy.legs.addAll(legs);
            return copy;
        }

        /** The set fields, in wire order, as plain values -- what the encoder writes. */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            if (id != null) {
                map.put("id", id);
            }
            if (qty != null) {
                map.put("qty", qty);
            }
            if (price != null) {
                map.put("price", price);
            }
            if (status != null) {
                map.put("status", status);
            }
            if (party != null) {
                map.put("party", party.toMap());
            }
            if (!legs.isEmpty()) {
                map.put("legs", List.copyOf(legs));
            }
            return map;
        }

        /** The rebuild path: a plain map, coerced field by field; an unknown key is refused. */
        public static Order fromMap(Map<String, Object> fields) {
            Order order = new Order();
            fields.forEach(order::set);
            return order;
        }

        /** Set one field from whatever a transform or a map handed over. */
        Object set(String field, Object value) {
            switch (field) {
                case "id" -> {
                    Object previous = id;
                    id = value == null ? null : String.valueOf(value);
                    return previous;
                }
                case "qty" -> {
                    Object previous = qty;
                    qty = value == null ? null
                            : value instanceof Number number ? number.longValue()
                            : Long.parseLong(String.valueOf(value).trim());
                    return previous;
                }
                case "price" -> {
                    Object previous = price;
                    price = value == null ? null
                            : value instanceof BigDecimal decimal ? decimal
                            : new BigDecimal(String.valueOf(value).trim());
                    return previous;
                }
                case "status" -> {
                    Object previous = status;
                    status = value == null ? null : String.valueOf(value);
                    return previous;
                }
                case "party" -> {
                    Object previous = party;
                    party = value == null ? null : Party.from(value);
                    return previous;
                }
                case "legs" -> {
                    List<String> previous = legs.isEmpty() ? null : List.copyOf(legs);
                    legs.clear();
                    if (value instanceof List<?> list) {
                        list.forEach(leg -> legs.add(String.valueOf(leg)));
                    } else if (value != null) {
                        legs.add(String.valueOf(value));
                    }
                    return previous;
                }
                default -> throw new IllegalArgumentException(
                        "no field '" + field + "' in payload type " + TYPE);
            }
        }

        boolean isSet(String field) {
            return switch (field) {
                case "id" -> id != null;
                case "qty" -> qty != null;
                case "price" -> price != null;
                case "status" -> status != null;
                case "party" -> party != null;
                case "legs" -> !legs.isEmpty();
                default -> false;
            };
        }

        @Override
        public String toString() {
            return "Order" + toMap();
        }
    }

    /** The nested message. */
    public static final class Party {

        private String name;
        private String role;

        public Party() {
        }

        public Party(String name, String role) {
            this.name = name;
            this.role = role;
        }

        public String name() {
            return name;
        }

        public String role() {
            return role;
        }

        public Party copy() {
            return new Party(name, role);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            if (name != null) {
                map.put("name", name);
            }
            if (role != null) {
                map.put("role", role);
            }
            return map;
        }

        static Party from(Object value) {
            if (value instanceof Party party) {
                return party;
            }
            if (value instanceof Map<?, ?> map) {
                Party party = new Party();
                map.forEach((key, field) -> party.set(String.valueOf(key), field));
                return party;
            }
            throw new IllegalArgumentException("party is an object, not " + value);
        }

        Object set(String field, Object value) {
            switch (field) {
                case "name" -> {
                    Object previous = name;
                    name = value == null ? null : String.valueOf(value);
                    return previous;
                }
                case "role" -> {
                    Object previous = role;
                    role = value == null ? null : String.valueOf(value);
                    return previous;
                }
                default -> throw new IllegalArgumentException(
                        "no field 'party." + field + "' in payload type " + TYPE);
            }
        }

        boolean isSet(String field) {
            return switch (field) {
                case "name" -> name != null;
                case "role" -> role != null;
                default -> false;
            };
        }

        @Override
        public String toString() {
            return "Party" + toMap();
        }
    }

    /** The lazy map over an {@link Order}: reads counted, writes to the order, copy = deep copy. */
    private final class OrderView extends AbstractMap<String, Object> implements FieldView {

        private final Order order;

        OrderView(Order order) {
            this.order = Objects.requireNonNull(order, "order");
        }

        @Override
        public PayloadType type() {
            return TYPE;
        }

        @Override
        public Object target() {
            return order;
        }

        @Override
        public FieldView copy() {
            copies.incrementAndGet();
            return new OrderView(order.copy());
        }

        @Override
        public Object get(Object key) {
            String field = String.valueOf(key);
            if (!order.isSet(field)) {
                return null;
            }
            read(field);
            return switch (field) {
                case "id" -> order.id;
                case "qty" -> order.qty;
                case "price" -> order.price;
                case "status" -> order.status;
                case "party" -> new PartyView(order.party);
                case "legs" -> order.legs();
                default -> null;
            };
        }

        @Override
        public boolean containsKey(Object key) {
            return order.isSet(String.valueOf(key));
        }

        @Override
        public Object put(String key, Object value) {
            return order.set(key, value);
        }

        @Override
        public Object remove(Object key) {
            String field = String.valueOf(key);
            return order.isSet(field) ? order.set(field, null) : null;
        }

        @Override
        public int size() {
            return present().size();
        }

        /** The set fields, in schema order: what {@code keySet()} answers without a read. */
        private List<String> present() {
            List<String> present = new ArrayList<>(FIELDS.size());
            for (String field : FIELDS) {
                if (order.isSet(field)) {
                    present.add(field);
                }
            }
            return present;
        }

        @Override
        public Set<String> keySet() {
            return Collections.unmodifiableSet(new LinkedHashSet<>(present()));
        }

        @Override
        public Set<Map.Entry<String, Object>> entrySet() {
            return new LazyEntries(this, present());
        }
    }

    /** The lazy map over a {@link Party}, reads counted under {@code party.<field>}. */
    private final class PartyView extends AbstractMap<String, Object> implements FieldView {

        private static final List<String> PARTY_FIELDS = List.of("name", "role");

        private final Party party;

        PartyView(Party party) {
            this.party = party;
        }

        @Override
        public PayloadType type() {
            return TYPE;
        }

        @Override
        public Object target() {
            return party;
        }

        @Override
        public FieldView copy() {
            copies.incrementAndGet();
            return new PartyView(party.copy());
        }

        @Override
        public Object get(Object key) {
            String field = String.valueOf(key);
            if (!party.isSet(field)) {
                return null;
            }
            read("party." + field);
            return "name".equals(field) ? party.name : party.role;
        }

        @Override
        public boolean containsKey(Object key) {
            return party.isSet(String.valueOf(key));
        }

        @Override
        public Object put(String key, Object value) {
            return party.set(key, value);
        }

        @Override
        public Object remove(Object key) {
            String field = String.valueOf(key);
            return party.isSet(field) ? party.set(field, null) : null;
        }

        @Override
        public int size() {
            return present().size();
        }

        private List<String> present() {
            List<String> present = new ArrayList<>(2);
            for (String field : PARTY_FIELDS) {
                if (party.isSet(field)) {
                    present.add(field);
                }
            }
            return present;
        }

        @Override
        public Set<String> keySet() {
            return Collections.unmodifiableSet(new LinkedHashSet<>(present()));
        }

        @Override
        public Set<Map.Entry<String, Object>> entrySet() {
            return new LazyEntries(this, present());
        }
    }

    /** The set fields of a view as entries whose values are read -- and counted -- on demand. */
    private static final class LazyEntries extends AbstractSet<Map.Entry<String, Object>> {

        private final Map<String, Object> view;
        private final List<String> present;

        LazyEntries(Map<String, Object> view, List<String> present) {
            this.view = view;
            this.present = present;
        }

        @Override
        public Iterator<Map.Entry<String, Object>> iterator() {
            Iterator<String> fields = present.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return fields.hasNext();
                }

                @Override
                public Map.Entry<String, Object> next() {
                    String field = fields.next();
                    return new AbstractMap.SimpleImmutableEntry<>(field, view.get(field));
                }
            };
        }

        @Override
        public int size() {
            return present.size();
        }
    }
}
