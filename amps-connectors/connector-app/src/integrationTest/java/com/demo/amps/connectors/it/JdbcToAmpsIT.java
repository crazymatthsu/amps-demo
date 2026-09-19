package com.demo.amps.connectors.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.crankuptheamps.client.Client;
import com.demo.amps.testharness.AmpsFlow;
import com.demo.amps.testharness.AmpsTestServer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * A polled database query through the real connector application into a real AMPS instance:
 * the transport where a <em>removal</em> is not a message but an absence.
 *
 * <p>A {@code SNAPSHOT} poll re-runs the whole query and upserts every row, so inserts and
 * updates converge on the SOW the obvious way. The interesting one is the delete: nothing
 * arrives at all, and the source has to notice that a key it saw last poll is gone and turn
 * that into a {@code sow_delete}. Three phases in order, because each builds on the SOW the
 * last one left -- which is also how the connector experiences a database.
 *
 * <p>The keys travel end to end without ever being written twice: {@code key-columns} on the
 * source makes {@code account|symbol} the record's key, and {@code key.mode: PUBLISHER} with
 * an empty {@code key.fields} sends exactly that as the SowKey. It is the only arrangement
 * that can delete a row, because a row that vanished has no payload left to rebuild a key
 * from.
 *
 * <p>H2 rather than the PostgreSQL the runner ships with: the source speaks {@code java.sql}
 * and DriverManager, so a real in-process database exercises the same code paths -- DDL, a
 * real result set, real column labels and real types -- without a container of its own.
 *
 * <p>Skips rather than fails when {@code AMPS_IMAGE} is unset, like every other suite here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JdbcToAmpsIT {

    private static final String POSITIONS = "sow/connectors/positions";
    private static final String JDBC_URL = "jdbc:h2:mem:amps-connectors-it;DB_CLOSE_DELAY=-1";
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private AmpsTestServer server;
    private ConnectorAppRunner app;
    private Client json;
    private Connection database;

    @BeforeAll
    void startEverything() throws Exception {
        Optional<String> unavailable = AmpsTestServer.unavailableReason(AmpsFlow.AMPS_CONNECTORS);
        assumeThat(unavailable)
                .as("integration test prerequisites: %s", unavailable.orElse(""))
                .isEmpty();

        // The table first: a connector whose query fails is retried rather than fatal, but
        // starting from an empty database is what the polls are supposed to be reading.
        database = DriverManager.getConnection(JDBC_URL);
        execute("""
                CREATE TABLE positions (
                    account  VARCHAR(32)  NOT NULL,
                    symbol   VARCHAR(32)  NOT NULL,
                    quantity INT          NOT NULL,
                    avg_cost DECIMAL(18,4) NOT NULL,
                    PRIMARY KEY (account, symbol))""");

        server = AmpsTestServer.start(AmpsFlow.AMPS_CONNECTORS);
        app = ConnectorAppRunner.against(server.port())
                .connector("positions-jdbc")
                // Required by source.jdbc: a result-set row has no wire format, so the source
                // synthesises one as JSON keyed by column label.
                .set("format", "JSON")
                .set("source.jdbc.url", JDBC_URL)
                .set("source.jdbc.mode", "SNAPSHOT")
                // Quoted aliases: H2 upper-cases an unquoted column, and the labels are what
                // the key columns, the filters and the published payload are all written in.
                .set("source.jdbc.query", "SELECT account AS \"account\", symbol AS \"symbol\", "
                        + "quantity AS \"quantity\", avg_cost AS \"avgCost\" FROM positions")
                .set("source.jdbc.key-columns[0]", "account")
                .set("source.jdbc.key-columns[1]", "symbol")
                .set("source.jdbc.key-separator", "|")
                .set("source.jdbc.poll-interval", "500ms")
                .set("amps.topic", POSITIONS)
                .set("amps.message-type", "json")
                // No key.fields: the SOURCE supplies the key, which is the only key a
                // vanished row still has.
                .set("amps.key.mode", "PUBLISHER")
                .set("amps.on-delete", "SOW_DELETE")
                .set("amps.batch.max-messages", 100)
                .set("amps.batch.flush-interval", "200ms")
                .start();

        json = AmpsSow.connect(server.port(), "json", "jdbc-it-json");
    }

    @AfterAll
    void stopEverything() throws SQLException {
        if (app != null) {
            app.close();
        }
        if (json != null) {
            json.close();
        }
        if (server != null) {
            server.close();
        }
        if (database != null) {
            database.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("every row of the query becomes a SOW record keyed by its key columns")
    void insertedRowsAppearInTheSow() throws Exception {
        execute("INSERT INTO positions VALUES ('ACC-1', 'AAPL', 100, 101.5000)");
        execute("INSERT INTO positions VALUES ('ACC-2', 'MSFT', 250, 330.2500)");

        Awaitility.await("both rows, keyed account|symbol").atMost(PATIENCE)
                .untilAsserted(() -> {
                    List<AmpsSow.Record> records = AmpsSow.records(json, POSITIONS, "1=1");
                    assertThat(records).hasSize(2);
                    assertThat(records).extracting(AmpsSow.Record::sowKey)
                            .containsExactlyInAnyOrder("ACC-1|AAPL", "ACC-2|MSFT");
                    assertThat(records).filteredOn(r -> "ACC-1|AAPL".equals(r.sowKey()))
                            .singleElement()
                            .satisfies(record -> assertThat(record.data())
                                    .contains("\"quantity\":100")
                                    .contains("\"avgCost\":101.5000"));
                });
    }

    @Test
    @Order(2)
    @DisplayName("an updated row replaces its record rather than adding one")
    void updatedRowsReplaceTheirRecord() throws Exception {
        execute("UPDATE positions SET quantity = 175, avg_cost = 102.0000 "
                + "WHERE account = 'ACC-1' AND symbol = 'AAPL'");

        Awaitility.await("the same key, the new values").atMost(PATIENCE)
                .untilAsserted(() -> {
                    List<AmpsSow.Record> records = AmpsSow.records(json, POSITIONS, "1=1");
                    assertThat(records).hasSize(2);
                    assertThat(records).filteredOn(r -> "ACC-1|AAPL".equals(r.sowKey()))
                            .singleElement()
                            .satisfies(record -> assertThat(record.data())
                                    .contains("\"quantity\":175")
                                    .contains("\"avgCost\":102.0000"));
                });
    }

    @Test
    @Order(3)
    @DisplayName("a row that stops appearing is deleted from the SOW by its key")
    void deletedRowsLeaveTheSow() throws Exception {
        execute("DELETE FROM positions WHERE account = 'ACC-2'");

        // Nothing was published for this: the next poll simply did not return the key, and
        // the source turned that absence into a sow_delete by the key it remembered.
        Awaitility.await("ACC-2 gone, ACC-1 untouched").atMost(PATIENCE)
                .untilAsserted(() -> {
                    List<AmpsSow.Record> records = AmpsSow.records(json, POSITIONS, "1=1");
                    assertThat(records).singleElement()
                            .satisfies(record -> {
                                assertThat(record.sowKey()).isEqualTo("ACC-1|AAPL");
                                assertThat(record.data()).contains("\"quantity\":175");
                            });
                });
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = database.createStatement()) {
            statement.execute(sql);
        }
    }
}
