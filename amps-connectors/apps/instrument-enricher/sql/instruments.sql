-- The `instruments` reference table the instrument-enricher example enriches from, with
-- the six-symbol universe its simulated feed cycles through (K-0 .. K-4 are known; K-5 is
-- deliberately absent, so the demo also shows an UNKNOWN_SYMBOL alert).
--
-- Portable SQL, on purpose: it runs unchanged on PostgreSQL (psql -f) and on H2, and it is
-- RE-RUNNABLE -- H2's INIT=RUNSCRIPT executes it on every connection, and the lookup table
-- opens a connection per load, so each row is inserted only when its symbol is absent
-- rather than through MERGE or ON CONFLICT, which the two databases spell differently.
-- One statement per line, ending in `;`, so a test can execute it line by line as well.
--
-- Identifiers are unquoted, so PostgreSQL reports them in lower case and H2 in upper case;
-- the enricher matches a configured column to a label ignoring case for exactly that reason.
CREATE TABLE IF NOT EXISTS instruments (symbol VARCHAR(32) PRIMARY KEY, sedol VARCHAR(7), isin VARCHAR(12), currency VARCHAR(3), ric VARCHAR(32), updated_at TIMESTAMP);
INSERT INTO instruments (symbol, sedol, isin, currency, ric, updated_at) SELECT 'K-0', 'B0YQ5W0', 'GB00B0YQ5W00', 'GBP', 'K-0.L', TIMESTAMP '2026-09-19 08:00:00' WHERE NOT EXISTS (SELECT 1 FROM instruments WHERE symbol = 'K-0');
INSERT INTO instruments (symbol, sedol, isin, currency, ric, updated_at) SELECT 'K-1', 'B1YQ5W1', 'US00B1YQ5W11', 'USD', 'K-1.N', TIMESTAMP '2026-09-19 08:00:00' WHERE NOT EXISTS (SELECT 1 FROM instruments WHERE symbol = 'K-1');
INSERT INTO instruments (symbol, sedol, isin, currency, ric, updated_at) SELECT 'K-2', 'B2YQ5W2', 'DE00B2YQ5W22', 'EUR', 'K-2.DE', TIMESTAMP '2026-09-19 08:00:00' WHERE NOT EXISTS (SELECT 1 FROM instruments WHERE symbol = 'K-2');
INSERT INTO instruments (symbol, sedol, isin, currency, ric, updated_at) SELECT 'K-3', 'B3YQ5W3', 'JP00B3YQ5W33', 'JPY', 'K-3.T', TIMESTAMP '2026-09-19 08:00:00' WHERE NOT EXISTS (SELECT 1 FROM instruments WHERE symbol = 'K-3');
INSERT INTO instruments (symbol, sedol, isin, currency, ric, updated_at) SELECT 'K-4', 'B4YQ5W4', 'GB00B4YQ5W44', 'GBP', 'K-4.L', TIMESTAMP '2026-09-19 08:00:00' WHERE NOT EXISTS (SELECT 1 FROM instruments WHERE symbol = 'K-4');
