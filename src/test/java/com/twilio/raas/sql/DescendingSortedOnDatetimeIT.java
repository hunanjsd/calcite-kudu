package com.twilio.raas.sql;

import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.Type;
import org.apache.kudu.client.AsyncKuduSession;
import org.apache.kudu.client.KuduTable;
import org.apache.kudu.client.PartialRow;
import org.apache.kudu.client.Upsert;
import org.apache.kudu.test.KuduTestHarness;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(JUnit4.class)
public final class DescendingSortedOnDatetimeIT {
  private static final Logger logger = LoggerFactory.getLogger(JDBCQueryRunnerIT.class);

  private static String FIRST_SID = "SM1234857";
  private static String SECOND_SID = "SM123485789";
  private static String THIRD_SID = "SM485789123";

  public static String ACCOUNT_SID = "AC1234567";

  private static final String EVENT_DATE_FIELD = "event_date";

  @ClassRule
  public static KuduTestHarness testHarness = new KuduTestHarness();
  private static final String BASE_TABLE_NAME = "ReportCenter.AuditEvents";

  private static KuduTable TABLE;

  private static void validateRow(ResultSet rs,
                                  long expectedTimestamp,
                                  String expectedSid) throws SQLException {
    assertEquals("Mismatched account sid", ACCOUNT_SID,
        rs.getString("account_sid"));
    assertEquals("Mismatched event_date", expectedTimestamp,
        rs.getTimestamp(EVENT_DATE_FIELD).toInstant().toEpochMilli());
    assertEquals("Mismatched sid", expectedSid,
        rs.getString("sid"));
  }

  @BeforeClass
  public static void setup() throws Exception {
    final List<ColumnSchema> columns = Arrays.asList(
        new ColumnSchema.ColumnSchemaBuilder("account_sid", Type.STRING).key(true).build(),
        new ColumnSchema.ColumnSchemaBuilder("event_date", Type.UNIXTIME_MICROS).key(true).build(),
        new ColumnSchema.ColumnSchemaBuilder("sid", Type.STRING).key(true).build(),
        new ColumnSchema.ColumnSchemaBuilder("resource_type", Type.STRING).build());

    Schema schema = new Schema(columns);
    PartialRow row1 = schema.newPartialRow();
    row1.addTimestamp(EVENT_DATE_FIELD, new Timestamp(JDBCQueryRunner.EPOCH_FOR_REVERSE_SORT_IN_MILLISECONDS - (Instant.parse("2018-12-31T00:00:00.000Z").toEpochMilli())));
    PartialRow row2 = schema.newPartialRow();
    row2.addTimestamp(EVENT_DATE_FIELD, new Timestamp(JDBCQueryRunner.EPOCH_FOR_REVERSE_SORT_IN_MILLISECONDS - (Instant.parse("2019-01-01T00:00:00.000Z").toEpochMilli())));
    PartialRow row3 = schema.newPartialRow();
    row3.addTimestamp(EVENT_DATE_FIELD, new Timestamp(JDBCQueryRunner.EPOCH_FOR_REVERSE_SORT_IN_MILLISECONDS- (Instant.parse("2019-01-02T00:00:00.000Z").toEpochMilli())));
    PartialRow row4 = schema.newPartialRow();
    row4.addTimestamp(EVENT_DATE_FIELD, new Timestamp(JDBCQueryRunner.EPOCH_FOR_REVERSE_SORT_IN_MILLISECONDS - (Instant.parse("2019-01-03T00:00:00.000Z").toEpochMilli())));

    testHarness.getClient().createTable(BASE_TABLE_NAME, schema,
        new org.apache.kudu.client.CreateTableOptions()
            .addHashPartitions(Arrays.asList("account_sid"), 5)
            .setRangePartitionColumns(Arrays.asList("event_date"))
            .addRangePartition(row4, row3)
            .addRangePartition(row3, row2)
            .addRangePartition(row2, row1)
            .setNumReplicas(1));
    final AsyncKuduSession insertSession = testHarness.getAsyncClient().newSession();
    TABLE = testHarness.getClient().openTable(BASE_TABLE_NAME);

    final Upsert firstRowOp = TABLE.newUpsert();
    final PartialRow firstRowWrite = firstRowOp.getRow();
    firstRowWrite.addString("account_sid", JDBCQueryRunnerIT.ACCOUNT_SID);
    firstRowWrite.addTimestamp("event_date", new Timestamp(JDBCQueryRunner.EPOCH_FOR_REVERSE_SORT_IN_MILLISECONDS - (Instant.parse("2019-01-02T01:00:00.000Z").toEpochMilli())));
    firstRowWrite.addString("sid", DescendingSortedOnDatetimeIT.FIRST_SID);
    firstRowWrite.addString("resource_type", "message-body");
    insertSession.apply(firstRowOp).join();

    final Upsert secondRowOp = TABLE.newUpsert();
    final PartialRow secondRowWrite = secondRowOp.getRow();
    secondRowWrite.addString("account_sid", JDBCQueryRunnerIT.ACCOUNT_SID);
    secondRowWrite.addTimestamp("event_date", new Timestamp(JDBCQueryRunner.EPOCH_FOR_REVERSE_SORT_IN_MILLISECONDS- (Instant.parse("2019-01-02T02:25:00.000Z").toEpochMilli())));
    secondRowWrite.addString("sid", DescendingSortedOnDatetimeIT.SECOND_SID);
    secondRowWrite.addString("resource_type", "recording");
    insertSession.apply(secondRowOp).join();

    final Upsert thirdRowOp = TABLE.newUpsert();
    final PartialRow thirdRowWrite = thirdRowOp.getRow();
    thirdRowWrite.addString("account_sid", JDBCQueryRunnerIT.ACCOUNT_SID);
    thirdRowWrite.addTimestamp("event_date", new Timestamp(JDBCQueryRunner.EPOCH_FOR_REVERSE_SORT_IN_MILLISECONDS - (Instant.parse("2019-01-01T01:00:00.000Z").toEpochMilli())));
    thirdRowWrite.addString("sid", DescendingSortedOnDatetimeIT.THIRD_SID);
    thirdRowWrite.addString("resource_type", "sms-geographic-permission");
    insertSession.apply(thirdRowOp).join();
  }

  @AfterClass
  public static void tearDown() throws Exception {
    testHarness.getClient().deleteTable(BASE_TABLE_NAME);
  }

  @Test
  public void testQueryWithSortDesc() throws Exception {
    try (final JDBCQueryRunner runner = new JDBCQueryRunner(testHarness.getMasterAddressesAsString(), 1)) {
      List<Map<String, Object>> records = runner
          .executeSql("SELECT account_sid, event_date, sid FROM kudu.\"ReportCenter.AuditEvents\" order by event_date desc", (rs -> {
            final Map<String, Object> record = new HashMap<>();
            try {
              record.put("account_sid", rs.getString(1));
              record.put("event_date", rs.getLong(2));
              record.put("sid", rs.getString(3));
            }
            catch (SQLException failed) {
              //swallow it
            }
            return record;
          }))
          .toCompletableFuture().get();
      Assert.assertEquals("should get the rows back",
          3, records.size());
      Assert.assertEquals("First record's account sid should match",
          ACCOUNT_SID, records.get(0).get("account_sid"));
      Assert.assertTrue("First record's datetime is later than second's",
          Instant.ofEpochMilli((Long)records.get(0).get("event_date")).isAfter(Instant.ofEpochMilli((Long)records.get(1).get("event_date"))));
      Assert.assertTrue("Second record's datetime is later than first's",
          Instant.ofEpochMilli((Long)records.get(0).get("event_date")).isAfter(Instant.ofEpochMilli((Long)records.get(1).get("event_date"))));
    }
  }

  @Test
  public void testQueryWithSortAsc() throws Exception {
    try (final JDBCQueryRunner runner = new JDBCQueryRunner(testHarness.getMasterAddressesAsString(), 1)) {
      List<Map<String, Object>> records = runner
          .executeSql("SELECT account_sid, event_date, sid FROM kudu.\"ReportCenter.AuditEvents\" order by event_date asc", (rs -> {
            final Map<String, Object> record = new HashMap<>();
            try {
              record.put("account_sid", rs.getString(1));
              record.put("event_date", rs.getLong(2));
              record.put("sid", rs.getString(3));
            }
            catch (SQLException failed) {
              //swallow it
            }
            return record;
          }))
          .toCompletableFuture().get();
      Assert.assertEquals("should get the rows back",
          3, records.size());
      Assert.assertEquals("First record's account sid should match",
          ACCOUNT_SID, records.get(0).get("account_sid"));
      Assert.assertTrue("First record's datetime is earlier than second's",
          Instant.ofEpochMilli((Long)records.get(0).get("event_date")).isBefore(Instant.ofEpochMilli((Long)records.get(1).get("event_date"))));
      Assert.assertTrue("Second record's datetime is earlier than first's",
          Instant.ofEpochMilli((Long)records.get(0).get("event_date")).isBefore(Instant.ofEpochMilli((Long)records.get(1).get("event_date"))));
    }
  }

  @Test
  public void testQueryWithPredicates() throws Exception {
    try (final JDBCQueryRunner runner = new JDBCQueryRunner(testHarness.getMasterAddressesAsString(), 1)) {
      List<Map<String, Object>> records = runner
          .executeSql("SELECT account_sid, event_date, sid FROM kudu.\"ReportCenter.AuditEvents\" where event_date >= TIMESTAMP'2019-01-01 00:00:00' and event_date < TIMESTAMP'2019-01-02 00:00:00'", (rs -> {
            final Map<String, Object> record = new HashMap<>();
            try {
              record.put("account_sid", rs.getString(1));
              record.put("event_date", rs.getLong(2));
              record.put("sid", rs.getString(3));
            }
            catch (SQLException failed) {
              //swallow it
            }
            return record;
          }))
          .toCompletableFuture().get();
      Assert.assertEquals("should get the rows back",
          1, records.size());
      Assert.assertEquals("First record's account sid should match",
          ACCOUNT_SID, records.get(0).get("account_sid"));
      Assert.assertTrue("Record's datetime is of third upserted record",
          Instant.ofEpochMilli((Long)records.get(0).get("event_date")).equals(Instant.parse("2019-01-01T01:00:00.000Z")));
    }
  }

  @Test
  public void testQueryWithPredicatesAndSortAsc() throws Exception {
    try (final JDBCQueryRunner runner = new JDBCQueryRunner(testHarness.getMasterAddressesAsString(), 1)) {
      List<Map<String, Object>> records = runner
          .executeSql("SELECT account_sid, event_date, sid FROM kudu.\"ReportCenter.AuditEvents\" where event_date >= TIMESTAMP'2019-01-02 00:00:00' and event_date < TIMESTAMP'2019-01-03 00:00:00' order by event_date asc", (rs -> {
            final Map<String, Object> record = new HashMap<>();
            try {
              record.put("account_sid", rs.getString(1));
              record.put("event_date", rs.getLong(2));
              record.put("sid", rs.getString(3));
            }
            catch (SQLException failed) {
              //swallow it
            }
            return record;
          }))
          .toCompletableFuture().get();
      Assert.assertEquals("should get the rows back",
          2, records.size());
      Assert.assertEquals("First record's account sid should match",
          ACCOUNT_SID, records.get(0).get("account_sid"));
      Assert.assertTrue("First record's datetime is earlier than second's",
          Instant.ofEpochMilli((Long)records.get(0).get("event_date")).isBefore(Instant.ofEpochMilli((Long)records.get(1).get("event_date"))));
    }
  }

  @Test
  public void testQueryWithPredicatesAndSortDesc() throws Exception {
    try (final JDBCQueryRunner runner = new JDBCQueryRunner(testHarness.getMasterAddressesAsString(), 1)) {
      List<Map<String, Object>> records = runner
          .executeSql("SELECT account_sid, event_date, sid FROM kudu.\"ReportCenter.AuditEvents\" where event_date >= TIMESTAMP'2019-01-02 00:00:00' and event_date < TIMESTAMP'2019-01-03 00:00:00' order by event_date desc", (rs -> {
            final Map<String, Object> record = new HashMap<>();
            try {
              record.put("account_sid", rs.getString(1));
              record.put("event_date", rs.getLong(2));
              record.put("sid", rs.getString(3));
            }
            catch (SQLException failed) {
              //swallow it
            }
            return record;
          }))
          .toCompletableFuture().get();
      Assert.assertEquals("should get the rows back",
          2, records.size());
      Assert.assertEquals("First record's account sid should match",
          ACCOUNT_SID, records.get(0).get("account_sid"));
      Assert.assertTrue("First record's datetime is later than second's",
          Instant.ofEpochMilli((Long)records.get(0).get("event_date")).isAfter(Instant.ofEpochMilli((Long)records.get(1).get("event_date"))));
    }
  }

  @Test
  public void testSortWithFilter() throws Exception {
    try (final JDBCQueryRunner runner = new JDBCQueryRunner(testHarness.getMasterAddressesAsString(), 1)) {
      String url = String.format(JDBCQueryRunner.CALCITE_MODEL_TEMPLATE, testHarness.getMasterAddressesAsString());
      try (Connection conn = DriverManager.getConnection(url)) {
        String firstBatchSqlFormat = "SELECT * FROM kudu.\"ReportCenter" +
            ".AuditEvents\" "
            + "WHERE account_sid = '%s' "
            + "ORDER BY event_date desc, account_sid asc ";
        String firstBatchSql = String.format(firstBatchSqlFormat, ACCOUNT_SID);

        // verify plan
        ResultSet rs = conn.createStatement().executeQuery("EXPLAIN PLAN FOR " + firstBatchSql);
        String plan = SqlUtil.getExplainPlan(rs);
        String expectedPlanFormat = "KuduToEnumerableRel\n"
            +"  KuduSortRel(sort0=[$1], sort1=[$0], dir0=[DESC], dir1=[ASC])\n"
            +"    KuduFilterRel(Scan 1=[account_sid EQUAL AC1234567])\n"
            +"      KuduQuery(table=[[kudu, ReportCenter.AuditEvents]])\n";
        String expectedPlan = String.format(expectedPlanFormat, ACCOUNT_SID);
        assertEquals("Unexpected plan ", expectedPlan, plan);
        rs = conn.createStatement().executeQuery(firstBatchSql);

        assertTrue(rs.next());
        validateRow(rs, 1546395900000L, DescendingSortedOnDatetimeIT.SECOND_SID);
        assertTrue(rs.next());
        validateRow(rs, 1546390800000L, DescendingSortedOnDatetimeIT.FIRST_SID);
        assertTrue(rs.next());
        validateRow(rs, 1546304400000L, DescendingSortedOnDatetimeIT.THIRD_SID);
      }
    }
  }

  @Test
  public void testAscendingSortOnDescendingFieldWithFilter() throws Exception {
    try (final JDBCQueryRunner runner = new JDBCQueryRunner(testHarness.getMasterAddressesAsString(), 1)) {
      String url = String.format(JDBCQueryRunner.CALCITE_MODEL_TEMPLATE, testHarness.getMasterAddressesAsString());
      try (Connection conn = DriverManager.getConnection(url)) {
        String firstBatchSqlFormat = "SELECT * FROM kudu.\"ReportCenter" +
            ".AuditEvents\" "
            + "WHERE account_sid = '%s' "
            + "ORDER BY event_date asc ";
        String firstBatchSql = String.format(firstBatchSqlFormat, ACCOUNT_SID);

        // verify plan
        ResultSet rs = conn.createStatement().executeQuery("EXPLAIN PLAN FOR " + firstBatchSql);
        String plan = SqlUtil.getExplainPlan(rs);
        String expectedPlanFormat = "EnumerableSort(sort0=[$1], dir0=[ASC])\n" +
            "  KuduToEnumerableRel\n" +
            "    KuduFilterRel(Scan 1=[account_sid EQUAL AC1234567])\n" +
            "      KuduQuery(table=[[kudu, ReportCenter.AuditEvents]])\n";
        String expectedPlan = String.format(expectedPlanFormat, ACCOUNT_SID);
        assertEquals("Unexpected plan ", expectedPlan, plan);
        rs = conn.createStatement().executeQuery(firstBatchSql);

        assertTrue(rs.next());
        validateRow(rs, 1546304400000L, DescendingSortedOnDatetimeIT.THIRD_SID);
        assertTrue(rs.next());
        validateRow(rs, 1546390800000L, DescendingSortedOnDatetimeIT.FIRST_SID);
        assertTrue(rs.next());
        validateRow(rs, 1546395900000L, DescendingSortedOnDatetimeIT.SECOND_SID);
      }
    }
  }

  @Test
  public void testSortWithFilterAndLimit() throws Exception {
    try (final JDBCQueryRunner runner = new JDBCQueryRunner(testHarness.getMasterAddressesAsString(), 1)) {
      String url = String.format(JDBCQueryRunner.CALCITE_MODEL_TEMPLATE, testHarness.getMasterAddressesAsString());
      try (Connection conn = DriverManager.getConnection(url)) {
        String firstBatchSqlFormat = "SELECT * FROM kudu.\"ReportCenter" +
            ".AuditEvents\" "
            + "WHERE account_sid = '%s' and event_date <= TIMESTAMP'2019-01-02 00:00:00' "
            + "ORDER BY event_date desc "
            + "LIMIT 1";
        String firstBatchSql = String.format(firstBatchSqlFormat, ACCOUNT_SID);

        // verify plan
        ResultSet rs = conn.createStatement().executeQuery("EXPLAIN PLAN FOR " + firstBatchSql);
        String plan = SqlUtil.getExplainPlan(rs);
        String expectedPlanFormat = "EnumerableLimit(fetch=[1])\n" +
            "  KuduToEnumerableRel\n" +
            "    KuduSortRel(sort0=[$1], dir0=[DESC])\n" +
            "      KuduFilterRel(Scan 1=[account_sid EQUAL AC1234567 , event_date LESS_EQUAL 1546387200000000])\n" +
            "        KuduQuery(table=[[kudu, ReportCenter.AuditEvents]])\n";
        String expectedPlan = String.format(expectedPlanFormat, ACCOUNT_SID);
        assertEquals("Unexpected plan ", expectedPlan, plan);
        rs = conn.createStatement().executeQuery(firstBatchSql);

        assertTrue(rs.next());
        validateRow(rs, 1546304400000L, DescendingSortedOnDatetimeIT.THIRD_SID);
      }
    }
  }

  @Test
  public void testSortOnNonPkFieldWithFilter() throws Exception {
    try (final JDBCQueryRunner runner = new JDBCQueryRunner(testHarness.getMasterAddressesAsString(), 1)) {
      String url = String.format(JDBCQueryRunner.CALCITE_MODEL_TEMPLATE, testHarness.getMasterAddressesAsString());
      try (Connection conn = DriverManager.getConnection(url)) {
        String firstBatchSqlFormat = "SELECT * FROM kudu.\"ReportCenter" +
            ".AuditEvents\" "
            + "WHERE account_sid = '%s' "
            + "ORDER BY event_date desc, resource_type asc ";
        String firstBatchSql = String.format(firstBatchSqlFormat, ACCOUNT_SID);

        // verify plan
        ResultSet rs = conn.createStatement().executeQuery("EXPLAIN PLAN FOR " + firstBatchSql);
        String plan = SqlUtil.getExplainPlan(rs);
        String expectedPlanFormat = "EnumerableSort(sort0=[$1], sort1=[$3], dir0=[DESC], dir1=[ASC])\n" +
            "  KuduToEnumerableRel\n" +
            "    KuduFilterRel(Scan 1=[account_sid EQUAL AC1234567])\n" +
            "      KuduQuery(table=[[kudu, ReportCenter.AuditEvents]])\n";
        String expectedPlan = String.format(expectedPlanFormat, ACCOUNT_SID);
        assertEquals("Unexpected plan ", expectedPlan, plan);
        rs = conn.createStatement().executeQuery(firstBatchSql);

        assertTrue(rs.next());
        validateRow(rs, 1546395900000L, DescendingSortedOnDatetimeIT.SECOND_SID);
        assertTrue(rs.next());
        validateRow(rs, 1546390800000L, DescendingSortedOnDatetimeIT.FIRST_SID);
        assertTrue(rs.next());
        validateRow(rs, 1546304400000L, DescendingSortedOnDatetimeIT.THIRD_SID);
      }
    }
  }
}
