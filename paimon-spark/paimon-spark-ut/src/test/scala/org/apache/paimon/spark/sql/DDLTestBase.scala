/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.spark.sql

import org.apache.paimon.catalog.Identifier
import org.apache.paimon.schema.Schema
import org.apache.paimon.spark.PaimonSparkTestBase
import org.apache.paimon.types.DataTypes

import org.apache.spark.SparkException
import org.apache.spark.sql.Row
import org.junit.jupiter.api.Assertions

import java.sql.{Date, Timestamp}
import java.time.LocalDateTime

abstract class DDLTestBase extends PaimonSparkTestBase {

  import testImplicits._

  test("Paimon DDL: create append table with not null") {
    withTable("T") {
      sql("CREATE TABLE T (id INT NOT NULL, name STRING)")

      val e1 = intercept[SparkException] {
        sql("""INSERT INTO T VALUES (1, "a"), (2, "b"), (null, "c")""")
      }
      Assertions.assertTrue(e1.getMessage().contains("Cannot write null to non-null column"))

      sql("""INSERT INTO T VALUES (1, "a"), (2, "b"), (3, null)""")
      checkAnswer(
        sql("SELECT * FROM T ORDER BY id"),
        Seq((1, "a"), (2, "b"), (3, null)).toDF()
      )

      val schema = spark.table("T").schema
      Assertions.assertEquals(schema.size, 2)
      Assertions.assertFalse(schema("id").nullable)
      Assertions.assertTrue(schema("name").nullable)
    }
  }
  test("Paimon DDL: create primary-key table with not null") {
    withTable("T") {
      sql("""
            |CREATE TABLE T (id INT, name STRING, pt STRING)
            |TBLPROPERTIES ('primary-key' = 'id,pt')
            |""".stripMargin)

      val e1 = intercept[SparkException] {
        sql("""INSERT INTO T VALUES (1, "a", "pt1"), (2, "b", null)""")
      }
      Assertions.assertTrue(e1.getMessage().contains("Cannot write null to non-null column"))

      val e2 = intercept[SparkException] {
        sql("""INSERT INTO T VALUES (1, "a", "pt1"), (null, "b", "pt2")""")
      }
      Assertions.assertTrue(e2.getMessage().contains("Cannot write null to non-null column"))

      sql("""INSERT INTO T VALUES (1, "a", "pt1"), (2, "b", "pt1"), (3, null, "pt2")""")
      checkAnswer(
        sql("SELECT * FROM T ORDER BY id"),
        Seq((1, "a", "pt1"), (2, "b", "pt1"), (3, null, "pt2")).toDF()
      )

      val schema = spark.table("T").schema
      Assertions.assertEquals(schema.size, 3)
      Assertions.assertFalse(schema("id").nullable)
      Assertions.assertTrue(schema("name").nullable)
      Assertions.assertFalse(schema("pt").nullable)
    }
  }

  test("Paimon DDL: write nullable expression to non-null column") {
    withTable("T") {
      sql("""
            |CREATE TABLE T (id INT NOT NULL, ts TIMESTAMP NOT NULL)
            |""".stripMargin)

      sql("INSERT INTO T SELECT 1, TO_TIMESTAMP('2024-07-01 16:00:00')")

      checkAnswer(
        sql("SELECT * FROM T ORDER BY id"),
        Row(1, Timestamp.valueOf("2024-07-01 16:00:00")) :: Nil
      )
    }
  }

  test("Paimon DDL: create database with location with filesystem catalog") {
    withTempDir {
      dBLocation =>
        withDatabase("paimon_db") {
          val error = intercept[Exception] {
            spark.sql(s"CREATE DATABASE paimon_db LOCATION '${dBLocation.getCanonicalPath}'")
          }.getMessage
          assert(
            error.contains("Cannot specify location for a database when using fileSystem catalog."))
        }
    }
  }

  test("Paimon DDL: create other table with paimon SparkCatalog") {
    withTable("paimon_tbl1", "paimon_tbl2", "parquet_tbl") {
      spark.sql(s"CREATE TABLE paimon_tbl1 (id int) USING paimon")
      spark.sql(s"CREATE TABLE paimon_tbl2 (id int)")
      val error = intercept[Exception] {
        spark.sql(s"CREATE TABLE parquet_tbl (id int) USING parquet")
      }.getMessage
      assert(error.contains("does not support format table"))
    }
  }

  test("Paimon DDL: create table without using paimon") {
    withTable("paimon_tbl") {
      sql("CREATE TABLE paimon_tbl (id int)")
      assert(!loadTable("paimon_tbl").options().containsKey("provider"))
    }
  }

  fileFormats.foreach {
    format =>
      test(s"Paimon DDL: create table with char/varchar/string, file.format: $format") {
        withTable("paimon_tbl") {
          spark.sql(
            s"""
               |CREATE TABLE paimon_tbl (id int, col_s1 char(9), col_s2 varchar(10), col_s3 string)
               |USING PAIMON
               |TBLPROPERTIES ('file.format' = '$format')
               |""".stripMargin)

          spark.sql(s"""
                       |insert into paimon_tbl values
                       |(1, 'Wednesday', 'Wednesday', 'Wednesday'),
                       |(2, 'Friday', 'Friday', 'Friday')
                       |""".stripMargin)

          // check description
          checkAnswer(
            spark
              .sql(s"DESC paimon_tbl")
              .select("col_name", "data_type")
              .where("col_name LIKE 'col_%'")
              .orderBy("col_name"),
            Row("col_s1", "char(9)") :: Row("col_s2", "varchar(10)") :: Row(
              "col_s3",
              "string") :: Nil
          )

          // check select
          if (format == "orc" && !gteqSpark3_4) {
            // Orc reader will right trim the char type, e.g. "Friday   " => "Friday" (see orc's `CharTreeReader`)
            // and Spark has a conf `spark.sql.readSideCharPadding` to auto padding char only since 3.4 (default true)
            // So when using orc with Spark3.4-, here will return "Friday"
            checkAnswer(
              spark.sql(s"select col_s1 from paimon_tbl where id = 2"),
              Row("Friday") :: Nil
            )
            // Spark will auto create the filter like Filter(isnotnull(col_s1#124) AND (col_s1#124 = Friday   ))
            // for char type, so here will not return any rows
            checkAnswer(
              spark.sql(s"select col_s1 from paimon_tbl where col_s1 = 'Friday'"),
              Nil
            )
          } else {
            checkAnswer(
              spark.sql(s"select col_s1 from paimon_tbl where id = 2"),
              Row("Friday   ") :: Nil
            )
            checkAnswer(
              spark.sql(s"select col_s1 from paimon_tbl where col_s1 = 'Friday'"),
              Row("Friday   ") :: Nil
            )
          }
          checkAnswer(
            spark.sql(s"select col_s2 from paimon_tbl where col_s2 = 'Friday'"),
            Row("Friday") :: Nil
          )
          checkAnswer(
            spark.sql(s"select col_s3 from paimon_tbl where col_s3 = 'Friday'"),
            Row("Friday") :: Nil
          )
        }
      }
  }

  test("Paimon DDL: write with char") {
    withTable("paimon_tbl") {
      spark.sql(s"""
                   |CREATE TABLE paimon_tbl (id int, c char(6))
                   |USING PAIMON
                   |""".stripMargin)

      withSparkSQLConf("spark.sql.legacy.charVarcharAsString" -> "true") {
        sql("INSERT INTO paimon_tbl VALUES (1, 'ab')")
      }

      withSparkSQLConf("spark.sql.legacy.charVarcharAsString" -> "false") {
        sql("INSERT INTO paimon_tbl VALUES (2, 'ab')")
      }

      if (gteqSpark3_4) {
        withSparkSQLConf("spark.sql.readSideCharPadding" -> "true") {
          checkAnswer(
            spark.sql("SELECT c FROM paimon_tbl ORDER BY id"),
            Row("ab    ") :: Row("ab    ") :: Nil)
        }
        withSparkSQLConf("spark.sql.readSideCharPadding" -> "false") {
          checkAnswer(
            spark.sql("SELECT c FROM paimon_tbl ORDER BY id"),
            Row("ab") :: Row("ab    ") :: Nil)
        }
      } else {
        checkAnswer(
          spark.sql("SELECT c FROM paimon_tbl ORDER BY id"),
          Row("ab") :: Row("ab    ") :: Nil)
      }
    }
  }

  test("Paimon DDL: create table with timestamp/timestamp_ntz") {
    Seq("orc", "parquet", "avro").foreach {
      format =>
        Seq(true, false).foreach {
          datetimeJava8APIEnabled =>
            withSparkSQLConf(
              "spark.sql.datetime.java8API.enabled" -> datetimeJava8APIEnabled.toString) {
              withTimeZone("Asia/Shanghai") {
                withTable("paimon_tbl") {
                  // Spark support create table with timestamp_ntz since 3.4
                  if (gteqSpark3_4) {
                    sql(s"""
                           |CREATE TABLE paimon_tbl (id int, binary BINARY, ts timestamp, ts_ntz timestamp_ntz)
                           |USING paimon
                           |TBLPROPERTIES ('file.format'='$format')
                           |""".stripMargin)

                    sql(s"INSERT INTO paimon_tbl VALUES (1, binary('b'), timestamp'2024-01-01 00:00:00', timestamp_ntz'2024-01-01 00:00:00')")
                    checkAnswer(
                      sql(s"SELECT ts, ts_ntz FROM paimon_tbl"),
                      Row(
                        if (datetimeJava8APIEnabled)
                          Timestamp.valueOf("2024-01-01 00:00:00").toInstant
                        else Timestamp.valueOf("2024-01-01 00:00:00"),
                        LocalDateTime.parse("2024-01-01T00:00:00")
                      )
                    )

                    // change time zone to UTC
                    withTimeZone("UTC") {
                      // todo: fix with orc
                      if (format != "orc")
                        checkAnswer(
                          sql(s"SELECT ts, ts_ntz FROM paimon_tbl"),
                          Row(
                            if (datetimeJava8APIEnabled)
                              Timestamp.valueOf("2023-12-31 16:00:00").toInstant
                            else Timestamp.valueOf("2023-12-31 16:00:00"),
                            LocalDateTime.parse("2024-01-01T00:00:00")
                          )
                        )
                    }
                  } else {
                    sql(s"""
                           |CREATE TABLE paimon_tbl (id int, binary BINARY, ts timestamp)
                           |USING paimon
                           |TBLPROPERTIES ('file.format'='$format')
                           |""".stripMargin)

                    sql(s"INSERT INTO paimon_tbl VALUES (1, binary('b'), timestamp'2024-01-01 00:00:00')")
                    checkAnswer(
                      sql(s"SELECT ts FROM paimon_tbl"),
                      Row(
                        if (datetimeJava8APIEnabled)
                          Timestamp.valueOf("2024-01-01 00:00:00").toInstant
                        else Timestamp.valueOf("2024-01-01 00:00:00"))
                    )

                    // For Spark 3.3 and below, time zone conversion is not supported,
                    // see TypeUtils.treatPaimonTimestampTypeAsSparkTimestampType
                    withTimeZone("UTC") {
                      // todo: fix with orc
                      if (format != "orc") {
                        checkAnswer(
                          sql(s"SELECT ts FROM paimon_tbl"),
                          Row(
                            if (datetimeJava8APIEnabled)
                              Timestamp.valueOf("2024-01-01 00:00:00").toInstant
                            else Timestamp.valueOf("2024-01-01 00:00:00"))
                        )
                      }
                    }
                  }
                }
              }
            }
        }
    }
  }

  test("Paimon DDL: create table with timestamp/timestamp_ntz using table API") {
    val identifier = Identifier.create("test", "paimon_tbl")
    try {
      withTimeZone("Asia/Shanghai") {
        val schema = Schema.newBuilder
          .column("ts", DataTypes.TIMESTAMP_WITH_LOCAL_TIME_ZONE())
          .column("ts_ntz", DataTypes.TIMESTAMP())
          .build
        paimonCatalog.createTable(identifier, schema, false)
        sql(
          s"INSERT INTO paimon_tbl VALUES (timestamp'2024-01-01 00:00:00', timestamp_ntz'2024-01-01 00:00:00')")

        // read by spark
        checkAnswer(
          sql(s"SELECT ts, ts_ntz FROM paimon_tbl"),
          Row(
            Timestamp.valueOf("2024-01-01 00:00:00"),
            if (gteqSpark3_4) LocalDateTime.parse("2024-01-01T00:00:00")
            else Timestamp.valueOf("2024-01-01 00:00:00")
          )
        )

        // read by table api
        // Due to previous design, read timestamp ltz type with spark 3.3 and below will cause problems,
        // skip testing it
        if (gteqSpark3_4) {
          val table = paimonCatalog.getTable(identifier)
          val builder = table.newReadBuilder.withProjection(Array[Int](0, 1))
          val splits = builder.newScan().plan().splits()
          builder.newRead
            .createReader(splits)
            .forEachRemaining(
              r => {
                Assertions.assertEquals(
                  Timestamp.valueOf("2023-12-31 16:00:00"),
                  r.getTimestamp(0, 6).toSQLTimestamp)
                Assertions.assertEquals(
                  Timestamp.valueOf("2024-01-01 00:00:00").toLocalDateTime,
                  r.getTimestamp(1, 6).toLocalDateTime)
              })
        }

        // change time zone to UTC
        withTimeZone("UTC") {
          // read by spark
          checkAnswer(
            sql(s"SELECT ts, ts_ntz FROM paimon_tbl"),
            Row(
              // For Spark 3.3 and below, time zone conversion is not supported,
              // see TypeUtils.treatPaimonTimestampTypeAsSparkTimestampType
              if (gteqSpark3_4) Timestamp.valueOf("2023-12-31 16:00:00")
              else Timestamp.valueOf("2024-01-01 00:00:00"),
              if (gteqSpark3_4) LocalDateTime.parse("2024-01-01T00:00:00")
              else Timestamp.valueOf("2024-01-01 00:00:00")
            )
          )

          // read by table api
          // Due to previous design, read timestamp ltz type with spark 3.3 and below will cause problems,
          // skip testing it
          if (gteqSpark3_4) {
            val table = paimonCatalog.getTable(identifier)
            val builder = table.newReadBuilder.withProjection(Array[Int](0, 1))
            val splits = builder.newScan().plan().splits()
            builder.newRead
              .createReader(splits)
              .forEachRemaining(
                r => {
                  Assertions.assertEquals(
                    Timestamp.valueOf("2023-12-31 16:00:00"),
                    r.getTimestamp(0, 6).toSQLTimestamp)
                  Assertions.assertEquals(
                    Timestamp.valueOf("2024-01-01 00:00:00").toLocalDateTime,
                    r.getTimestamp(1, 6).toLocalDateTime)
                })
          }
        }
      }
    } finally {
      paimonCatalog.dropTable(identifier, true)
    }
  }

  test("Paimon DDL: select table with timestamp and timestamp_ntz with filter") {
    Seq(true, false).foreach {
      datetimeJava8APIEnabled =>
        withSparkSQLConf(
          "spark.sql.datetime.java8API.enabled" -> datetimeJava8APIEnabled.toString) {
          withTable("paimon_tbl") {
            // Spark support create table with timestamp_ntz since 3.4
            if (gteqSpark3_4) {
              sql(s"""
                     |CREATE TABLE paimon_tbl (ts timestamp, ts_ntz timestamp_ntz)
                     |USING paimon
                     |""".stripMargin)
              sql(
                s"INSERT INTO paimon_tbl VALUES (timestamp'2024-01-01 00:00:00', timestamp_ntz'2024-01-01 00:00:00')")
              sql(
                s"INSERT INTO paimon_tbl VALUES (timestamp'2024-01-02 00:00:00', timestamp_ntz'2024-01-02 00:00:00')")
              sql(
                s"INSERT INTO paimon_tbl VALUES (timestamp'2024-01-03 00:00:00', timestamp_ntz'2024-01-03 00:00:00')")

              checkAnswer(
                sql(s"SELECT * FROM paimon_tbl where ts_ntz = timestamp_ntz'2024-01-01 00:00:00'"),
                Row(
                  if (datetimeJava8APIEnabled)
                    Timestamp.valueOf("2024-01-01 00:00:00").toInstant
                  else Timestamp.valueOf("2024-01-01 00:00:00"),
                  LocalDateTime.parse("2024-01-01T00:00:00")
                )
              )

              checkAnswer(
                sql(s"SELECT * FROM paimon_tbl where ts > timestamp'2024-01-02 00:00:00'"),
                Row(
                  if (datetimeJava8APIEnabled)
                    Timestamp.valueOf("2024-01-03 00:00:00").toInstant
                  else Timestamp.valueOf("2024-01-03 00:00:00"),
                  LocalDateTime.parse("2024-01-03T00:00:00")
                )
              )
            } else {
              sql(s"""
                     |CREATE TABLE paimon_tbl (ts timestamp)
                     |USING paimon
                     |""".stripMargin)
              sql(s"INSERT INTO paimon_tbl VALUES (timestamp'2024-01-01 00:00:00')")
              sql(s"INSERT INTO paimon_tbl VALUES (timestamp'2024-01-02 00:00:00')")
              sql(s"INSERT INTO paimon_tbl VALUES (timestamp'2024-01-03 00:00:00')")

              checkAnswer(
                sql(s"SELECT * FROM paimon_tbl where ts = timestamp'2024-01-01 00:00:00'"),
                Row(
                  if (datetimeJava8APIEnabled)
                    Timestamp.valueOf("2024-01-01 00:00:00").toInstant
                  else Timestamp.valueOf("2024-01-01 00:00:00"))
              )
            }
          }
        }
    }
  }

  test("Paimon DDL: create table with unsupported partitioned by") {
    val error = intercept[RuntimeException] {
      sql(s"""
             |CREATE TABLE T (id STRING, name STRING, pt STRING)
             |PARTITIONED BY (substr(pt, 1, 2))
             |""".stripMargin)
    }.getMessage
    assert(error.contains("Unsupported partition transform"))
  }

  test("Fix partition column generate wrong partition spec") {
    Seq(true, false).foreach {
      legacyPartName =>
        withTable("p_t") {
          spark.sql(s"""
                       |CREATE TABLE p_t (
                       |    id BIGINT,
                       |    c1 STRING
                       |) using paimon
                       |PARTITIONED BY (day binary)
                       |tblproperties('partition.legacy-name'='$legacyPartName');
                       |""".stripMargin)

          if (legacyPartName) {
            spark.sql("insert into table p_t values(1, 'a', cast('2021' as binary))")
            intercept[Exception] {
              spark.sql("SELECT * FROM p_t").collect()
            }
          } else {
            spark.sql("insert into table p_t values(1, 'a', cast('2021' as binary))")
            checkAnswer(spark.sql("SELECT * FROM p_t"), Row(1, "a", "2021".getBytes))
            val path = spark.sql("SELECT __paimon_file_path FROM p_t").collect()
            assert(path.length == 1)
            assert(path.head.getString(0).contains("/day=2021/"))
          }
        }

        withTable("p_t") {
          spark.sql(s"""
                       |CREATE TABLE p_t (
                       |    id BIGINT,
                       |    c1 STRING
                       |) using paimon
                       |PARTITIONED BY (day date)
                       |tblproperties('partition.legacy-name'='$legacyPartName');
                       |""".stripMargin)

          spark.sql("insert into table p_t values(1, 'a', cast('2021-01-01' as date))")
          checkAnswer(spark.sql("SELECT * FROM p_t"), Row(1, "a", Date.valueOf("2021-01-01")))

          val path = spark.sql("SELECT __paimon_file_path FROM p_t").collect()
          assert(path.length == 1)
          if (legacyPartName) {
            assert(path.head.getString(0).contains("/day=18628/"))
          } else {
            assert(path.head.getString(0).contains("/day=2021-01-01/"))
          }
        }
    }
  }

  test("Paimon DDL: create and drop external / managed table") {
    withTempDir {
      tbLocation =>
        withTable("external_tbl", "managed_tbl") {
          // create external table
          val error = intercept[UnsupportedOperationException] {
            sql(
              s"CREATE TABLE external_tbl (id INT) USING paimon LOCATION '${tbLocation.getCanonicalPath}'")
          }.getMessage
          assert(error.contains("not support"))

          // create managed table
          sql("CREATE TABLE managed_tbl (id INT) USING paimon")
          val table = loadTable("managed_tbl")
          val fileIO = table.fileIO()
          val tableLocation = table.location()

          // drop managed table
          sql("DROP TABLE managed_tbl")
          assert(!fileIO.exists(tableLocation))
        }
    }
  }

  test("Paimon DDL: rename table with catalog name") {
    sql("USE default")
    withTable("t1", "t2") {
      sql("CREATE TABLE t1 (id INT) USING paimon")
      sql("INSERT INTO t1 VALUES 1")
      sql("ALTER TABLE paimon.default.t1 RENAME TO paimon.default.t2")
      checkAnswer(sql("SELECT * FROM t2"), Row(1))

      assert(intercept[Exception] {
        sql("ALTER TABLE paimon.default.t2 RENAME TO spark_catalog.default.t2")
      }.getMessage.contains("Only supports operations within the same catalog"))
    }
  }
}
