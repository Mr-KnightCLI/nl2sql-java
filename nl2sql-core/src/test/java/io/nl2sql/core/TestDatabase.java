package io.nl2sql.core;

import io.nl2sql.core.config.Dialect;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;

/**
 * Creates a small, deterministic logistics database (carriers, customers, locations, shipments, invoices)
 * on any supported database, using each one's own column types. Existing fixture tables are dropped first.
 */
public final class TestDatabase {
    public static final List<String> TABLES = List.of("invoices", "shipments", "locations", "customers", "carriers");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final LocalDateTime ANCHOR = LocalDateTime.of(2026, 6, 30, 0, 0);

    private TestDatabase() {
    }

    public static Connection connect(String url, String user, String password) throws SQLException {
        SQLException last = null;
        for (int i = 0; i < 30; i++) { // containers may still be starting
            try {
                return user == null ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password);
            } catch (SQLException e) {
                last = e;
                if (url.startsWith("jdbc:sqlite:")) throw e;
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    public static void load(Connection c, Dialect d) throws SQLException {
        c.setAutoCommit(true);
        try (Statement st = c.createStatement()) {
            for (String t : TABLES) {
                try {
                    st.execute("DROP TABLE " + t);
                } catch (SQLException ignored) {
                    // did not exist
                }
            }
            st.execute("CREATE TABLE carriers (carrier_id " + i(d) + " PRIMARY KEY, carrier_name " + v(d, 100) + " NOT NULL, "
                    + "scac_code " + v(d, 4) + " NOT NULL, transport_mode " + v(d, 10) + " NOT NULL, contact_email " + v(d, 120) + ")");
            st.execute("CREATE TABLE customers (customer_id " + i(d) + " PRIMARY KEY, customer_name " + v(d, 100) + " NOT NULL, "
                    + "segment " + v(d, 20) + " NOT NULL, city " + v(d, 60) + " NOT NULL)");
            st.execute("CREATE TABLE locations (location_id " + i(d) + " PRIMARY KEY, location_name " + v(d, 100) + " NOT NULL, "
                    + "city " + v(d, 60) + " NOT NULL, location_type " + v(d, 20) + " NOT NULL)");
            st.execute("CREATE TABLE shipments (shipment_id " + i(d) + " PRIMARY KEY, shipment_ref " + v(d, 20) + " NOT NULL, "
                    + "customer_id " + i(d) + " NOT NULL REFERENCES customers(customer_id), "
                    + "carrier_id " + i(d) + " REFERENCES carriers(carrier_id), "
                    + "origin_location_id " + i(d) + " NOT NULL REFERENCES locations(location_id), "
                    + "dest_location_id " + i(d) + " NOT NULL REFERENCES locations(location_id), "
                    + "stat_cd " + v(d, 1) + " NOT NULL, weight_kg " + dec(d) + " NOT NULL, created_at " + ts(d) + " NOT NULL, "
                    + "promised_delivery_at " + ts(d) + " NOT NULL, delivered_at " + ts(d) + ", freight_cost " + dec(d) + ")");
            st.execute("CREATE TABLE invoices (invoice_id " + i(d) + " PRIMARY KEY, "
                    + "shipment_id " + i(d) + " NOT NULL REFERENCES shipments(shipment_id), "
                    + "amount " + dec(d) + " NOT NULL, currency " + v(d, 3) + " NOT NULL, status " + v(d, 10) + " NOT NULL)");
        }
        c.setAutoCommit(false);
        String[][] carriers = {{"BlueDart Surface", "BDSF", "LTL"}, {"Gati Express", "GATI", "LTL"}, {"TCI Freight", "TCIF", "FTL"},
                {"Delhivery", "DLVY", "PARCEL"}, {"Safexpress", "SAFX", "FTL"}, {"Maersk", "MAEU", "OCEAN"},
                {"VRL Logistics", "VRLL", "FTL"}, {"DHL Supply Chain", "DHLS", "LTL"}};
        try (PreparedStatement p = c.prepareStatement("INSERT INTO carriers VALUES (?,?,?,?,?)")) {
            for (int k = 0; k < carriers.length; k++) {
                p.setInt(1, k + 1);
                p.setString(2, carriers[k][0]);
                p.setString(3, carriers[k][1]);
                p.setString(4, carriers[k][2]);
                p.setString(5, "ops@" + carriers[k][1].toLowerCase() + ".example.com");
                p.addBatch();
            }
            p.executeBatch();
        }
        String[][] customers = {{"Reliance Retail", "RETAIL", "Mumbai"}, {"Tata Motors", "MANUFACTURING", "Pune"},
                {"Sun Pharma", "PHARMA", "Vadodara"}, {"Hindustan Unilever", "FMCG", "Mumbai"}, {"Asian Paints", "MANUFACTURING", "Mumbai"},
                {"Dr Reddys", "PHARMA", "Hyderabad"}, {"DMart", "RETAIL", "Thane"}, {"ITC Foods", "FMCG", "Kolkata"}};
        try (PreparedStatement p = c.prepareStatement("INSERT INTO customers VALUES (?,?,?,?)")) {
            for (int k = 0; k < customers.length; k++) {
                p.setInt(1, k + 1);
                p.setString(2, customers[k][0]);
                p.setString(3, customers[k][1]);
                p.setString(4, customers[k][2]);
                p.addBatch();
            }
            p.executeBatch();
        }
        String[][] locations = {{"Bhiwandi DC", "Bhiwandi", "WAREHOUSE"}, {"Chakan Plant", "Pune", "PLANT"},
                {"Hoskote DC", "Bangalore", "WAREHOUSE"}, {"JNPT Port", "Navi Mumbai", "PORT"}, {"Chennai Port", "Chennai", "PORT"},
                {"Whitefield Store", "Bangalore", "STORE"}};
        try (PreparedStatement p = c.prepareStatement("INSERT INTO locations VALUES (?,?,?,?)")) {
            for (int k = 0; k < locations.length; k++) {
                p.setInt(1, k + 1);
                p.setString(2, locations[k][0]);
                p.setString(3, locations[k][1]);
                p.setString(4, locations[k][2]);
                p.addBatch();
            }
            p.executeBatch();
        }
        Random rnd = new Random(42);
        int invoiceId = 1;
        try (PreparedStatement p = c.prepareStatement("INSERT INTO shipments VALUES (?,?,?,?,?,?,?,?,?,?,?,?)");
             PreparedStatement inv = c.prepareStatement("INSERT INTO invoices VALUES (?,?,?,?,?)")) {
            for (int s = 1; s <= 120; s++) {
                LocalDateTime created = ANCHOR.minusDays(rnd.nextInt(180)).minusHours(rnd.nextInt(24));
                LocalDateTime promised = created.plusDays(1 + rnd.nextInt(6));
                String status = promised.isAfter(ANCHOR) ? "T" : (rnd.nextInt(30) == 0 ? "X" : "D");
                LocalDateTime delivered = "D".equals(status) ? promised.plusHours(rnd.nextInt(10) < 7 ? -rnd.nextInt(20) : 2 + rnd.nextInt(90)) : null;
                int origin = 1 + rnd.nextInt(6);
                int dest = 1 + (origin + rnd.nextInt(5)) % 6;
                BigDecimal weight = BigDecimal.valueOf(50 + rnd.nextInt(18000)).setScale(1);
                BigDecimal cost = BigDecimal.valueOf(500 + rnd.nextInt(90000), 2).add(BigDecimal.valueOf(rnd.nextInt(20000)));
                p.setInt(1, s);
                p.setString(2, "SHP-" + (100000 + s));
                p.setInt(3, 1 + rnd.nextInt(customers.length));
                p.setInt(4, 1 + rnd.nextInt(carriers.length));
                p.setInt(5, origin);
                p.setInt(6, dest == origin ? (origin % 6) + 1 : dest);
                p.setString(7, status);
                p.setBigDecimal(8, weight);
                time(p, 9, created, d);
                time(p, 10, promised, d);
                time(p, 11, delivered, d);
                p.setBigDecimal(12, cost);
                p.addBatch();
                if (delivered != null) {
                    inv.setInt(1, invoiceId++);
                    inv.setInt(2, s);
                    inv.setBigDecimal(3, cost.multiply(BigDecimal.valueOf(118, 2)).setScale(2, java.math.RoundingMode.HALF_UP));
                    inv.setString(4, "INR");
                    inv.setString(5, rnd.nextInt(10) < 7 ? "PAID" : (rnd.nextInt(3) == 0 ? "DISPUTED" : "OPEN"));
                    inv.addBatch();
                }
            }
            p.executeBatch();
            inv.executeBatch();
        }
        c.commit();
        c.setAutoCommit(true);
    }

    private static void time(PreparedStatement p, int idx, LocalDateTime t, Dialect d) throws SQLException {
        if (t == null) {
            p.setNull(idx, d == Dialect.SQLITE ? java.sql.Types.VARCHAR : java.sql.Types.TIMESTAMP);
        } else if (d == Dialect.SQLITE) {
            p.setString(idx, t.format(TS)); // readable text, like most SQLite apps store dates
        } else {
            p.setTimestamp(idx, Timestamp.valueOf(t));
        }
    }

    private static String i(Dialect d) {
        return d == Dialect.ORACLE ? "NUMBER(10)" : "INTEGER";
    }

    private static String v(Dialect d, int n) {
        return (d == Dialect.ORACLE ? "VARCHAR2(" : "VARCHAR(") + n + ")";
    }

    private static String dec(Dialect d) {
        return d == Dialect.ORACLE ? "NUMBER(12,2)" : "DECIMAL(12,2)";
    }

    private static String ts(Dialect d) {
        return switch (d) {
            case MYSQL, MARIADB -> "DATETIME";
            case SQLSERVER -> "DATETIME2";
            case SQLITE -> "TEXT";
            default -> "TIMESTAMP";
        };
    }
}
