/*
 * Copyright 2016 CGnal S.p.A.
 *
 */

package com.cgnal.spark

import java.nio.ByteBuffer
import java.sql.Timestamp
import java.util.{ Calendar, TimeZone }

import net.opentsdb.core.TSDB
import org.apache.hadoop.hbase.client.Scan
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp
import org.apache.hadoop.hbase.filter.{ RegexStringComparator, RowFilter }
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.types._

import scala.collection.convert.decorateAsJava._
import scala.language.implicitConversions

package object opentsdb {

  implicit val writeForByte: (Iterator[DataPoint[Byte]], TSDB) => Unit = (it, tsdb) => {
    it.foreach(dp => {
      tsdb.addPoint(dp.metric, dp.timestamp, dp.value.asInstanceOf[Long], dp.tags.asJava)
    })
  }

  implicit val writeForShort: (Iterator[DataPoint[Short]], TSDB) => Unit = (it, tsdb) => {
    it.foreach(dp => {
      tsdb.addPoint(dp.metric, dp.timestamp, dp.value.asInstanceOf[Long], dp.tags.asJava)
    })
  }

  implicit val writeForInt: (Iterator[DataPoint[Int]], TSDB) => Unit = (it, tsdb) => {
    it.foreach(dp => {
      tsdb.addPoint(dp.metric, dp.timestamp, dp.value.asInstanceOf[Long], dp.tags.asJava)
    })
  }

  implicit val writeForLong: (Iterator[DataPoint[Long]], TSDB) => Unit = (it, tsdb) => {
    it.foreach(dp => {
      tsdb.addPoint(dp.metric, dp.timestamp, dp.value, dp.tags.asJava)
    })
  }

  implicit val writeForFloat: (Iterator[DataPoint[Float]], TSDB) => Unit = (it, tsdb) => {
    it.foreach(dp => {
      tsdb.addPoint(dp.metric, dp.timestamp, dp.value, dp.tags.asJava)
    })
  }

  implicit val writeForDouble: (Iterator[DataPoint[Double]], TSDB) => Unit = (it, tsdb) => {
    it.foreach(dp => {
      tsdb.addPoint(dp.metric, dp.timestamp, dp.value, dp.tags.asJava)
    })
  }

  private[opentsdb] def getUIDScan(metricName: String, tags: Map[String, String]) = {
    val scan = new Scan()
    val name: String = String.format("^(%s)$", Array(metricName, tags.keys.mkString("|"), tags.values.mkString("|")).mkString("|"))
    val keyRegEx: RegexStringComparator = new RegexStringComparator(name)
    val rowFilter: RowFilter = new RowFilter(CompareOp.EQUAL, keyRegEx)
    scan.setFilter(rowFilter)
    scan
  }

  private[opentsdb] def getMetricScan(
    bucket: Byte,
    tags: Map[String, String],
    metricUID: Array[Byte],
    tagKUIDs: Map[String, Array[Byte]],
    tagVUIDs: Map[String, Array[Byte]],
    interval: Option[(Long, Long)]
  ) = {
    val tagKKeys = tagKUIDs.keys.toArray
    val tagVKeys = tagVUIDs.keys.toArray
    val ntags = tags.filter(kv => tagKKeys.contains(kv._1) && tagVKeys.contains(kv._2))
    val tagKV = tagKUIDs.
      filter(kv => ntags.contains(kv._1)).
      map(k => (k._2, tagVUIDs(tags(k._1)))).
      map(l => l._1 ++ l._2).toList.sorted(Ordering.by((_: Array[Byte]).toIterable))
    val scan = new Scan()
    val name = if (bucket >= 0) {
      if (tagKV.nonEmpty)
        String.format("^%s%s.*%s.*$", bytes2hex(Array(bucket), "\\x"), bytes2hex(metricUID, "\\x"), bytes2hex(tagKV.flatten.toArray, "\\x"))
      else
        String.format("^%s%s.+$", bytes2hex(Array(bucket), "\\x"), bytes2hex(metricUID, "\\x"))
    } else {
      if (tagKV.nonEmpty)
        String.format("^%s.*%s.*$", bytes2hex(metricUID, "\\x"), bytes2hex(tagKV.flatten.toArray, "\\x"))
      else
        String.format("^%s.+$", bytes2hex(metricUID, "\\x"))
    }
    val keyRegEx: RegexStringComparator = new RegexStringComparator(name)
    val rowFilter: RowFilter = new RowFilter(CompareOp.EQUAL, keyRegEx)
    scan.setFilter(rowFilter)

    val minDate = (new Calendar.Builder().setTimeZone(TimeZone.getTimeZone("UTC")).setDate(1970, 0, 1).setTimeOfDay(0, 0, 0).build().getTime.getTime / 1000).toInt
    val maxDate = (new Calendar.Builder().setTimeZone(TimeZone.getTimeZone("UTC")).setDate(2099, 11, 31).setTimeOfDay(23, 59, 0).build().getTime.getTime / 1000).toInt

    val stDateBuffer = ByteBuffer.allocate(4)
    val endDateBuffer = ByteBuffer.allocate(4)

    interval.fold({
      stDateBuffer.putInt(minDate)
      endDateBuffer.putInt(maxDate)
    })(interval => {
      stDateBuffer.putInt(interval._1.toInt)
      endDateBuffer.putInt(interval._2.toInt)
    })
    if (bucket >= 0) {
      if (tagKV.nonEmpty) {
        scan.setStartRow(hexStringToByteArray(bytes2hex(Array(bucket), "\\x") + bytes2hex(metricUID, "\\x") + bytes2hex(stDateBuffer.array(), "\\x") + bytes2hex(tagKV.flatten.toArray, "\\x")))
        scan.setStopRow(hexStringToByteArray(bytes2hex(Array(bucket), "\\x") + bytes2hex(metricUID, "\\x") + bytes2hex(endDateBuffer.array(), "\\x") + bytes2hex(tagKV.flatten.toArray, "\\x")))
      } else {
        scan.setStartRow(hexStringToByteArray(bytes2hex(Array(bucket), "\\x") + bytes2hex(metricUID, "\\x") + bytes2hex(stDateBuffer.array(), "\\x")))
        scan.setStopRow(hexStringToByteArray(bytes2hex(Array(bucket), "\\x") + bytes2hex(metricUID, "\\x") + bytes2hex(endDateBuffer.array(), "\\x")))
      }
    } else {
      if (tagKV.nonEmpty) {
        scan.setStartRow(hexStringToByteArray(bytes2hex(metricUID, "\\x") + bytes2hex(stDateBuffer.array(), "\\x") + bytes2hex(tagKV.flatten.toArray, "\\x")))
        scan.setStopRow(hexStringToByteArray(bytes2hex(metricUID, "\\x") + bytes2hex(endDateBuffer.array(), "\\x") + bytes2hex(tagKV.flatten.toArray, "\\x")))
      } else {
        scan.setStartRow(hexStringToByteArray(bytes2hex(metricUID, "\\x") + bytes2hex(stDateBuffer.array(), "\\x")))
        scan.setStopRow(hexStringToByteArray(bytes2hex(metricUID, "\\x") + bytes2hex(endDateBuffer.array(), "\\x")))
      }
    }
    scan
  }

  private def bytes2hex(bytes: Array[Byte], sep: String): String = {
    sep + bytes.map("%02x".format(_)).mkString(sep)
  }

  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While"))
  private def hexStringToByteArray(s: String): Array[Byte] = {
    val sn = s.replace("\\x", "")
    val b: Array[Byte] = new Array[Byte](sn.length / 2)
    var i: Int = 0
    while (i < b.length) {
      {
        val index: Int = i * 2
        val v: Int = Integer.parseInt(sn.substring(index, index + 2), 16)
        b(i) = v.toByte
      }
      {
        i += 1
        i - 1
      }
    }
    b
  }

  implicit class OpenTSDBDataFrameReader(reader: DataFrameReader) {
    def opentsdb: DataFrame = reader.format("com.cgnal.spark.opentsdb").load
  }

  implicit class OpenTSDBDataFrameWriter(writer: DataFrameWriter) {
    def opentsdb(): Unit = writer.format("com.cgnal.spark.opentsdb").save
  }

  implicit class rddWrapper(rdd: RDD[DataPoint[Double]]) {

    def toDF(implicit sqlContext: SQLContext) = {
      val df = rdd.map {
        dp =>
          Row(new Timestamp(dp.timestamp), dp.metric, dp.value, dp.tags)
      }
      sqlContext.createDataFrame(df, StructType(
        Array(
          StructField("timestamp", TimestampType, nullable = false),
          StructField("metric", StringType, nullable = false),
          StructField("value", DoubleType, nullable = false),
          StructField("tags", DataTypes.createMapType(StringType, StringType), nullable = false)
        )
      ))
    }
  }

  implicit class RichTimestamp(val self: Timestamp) extends AnyVal {
    def -->(end: Timestamp): (Long, Long) = (
      self.getTime / 1000,
      end.getTime / 1000
    )
  }

}
