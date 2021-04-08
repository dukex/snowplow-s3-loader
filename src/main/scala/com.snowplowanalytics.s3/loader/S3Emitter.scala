/**
 * Copyright (c) 2014-2020 Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache
 * License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 *
 * See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.s3.loader

//Java
import java.io.ByteArrayInputStream
import java.time.{ Instant, ZonedDateTime, ZoneOffset }
import java.time.format.DateTimeFormatter


// Scala
import scala.util.control.NonFatal
import scala.jdk.CollectionConverters._

// SLF4j
import org.slf4j.LoggerFactory

// Tracker
import com.snowplowanalytics.snowplow.scalatracker.Tracker

import io.circe._
import io.circe.syntax._

// cats
import cats.data.Validated
import cats.Id

// AWS libs
import com.amazonaws.AmazonServiceException
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.auth.AWSCredentialsProvider

// This project
import sinks._
import serializers._
import model._

/**
 * Emitter for flushing data to S3.
 *
 * @param config S3Loader configuration
 * @param provider AWSCredentialsProvider
 * @param badSink Sink instance for not sent data
 * @param maxConnectionTime Max time for attempting to send S3
 * @param tracker Tracker instance
 */
class S3Emitter(
  config: S3Config,
  provider: AWSCredentialsProvider,
  badSink: ISink,
  maxConnectionTime: Long,
  tracker: Option[Tracker[Id]]
) {

  // create Amazon S3 Client
  val log = LoggerFactory.getLogger(getClass)
  val client = AmazonS3ClientBuilder
    .standard()
    .withCredentials(provider)
    .withEndpointConfiguration(new EndpointConfiguration(config.endpoint, config.region))
    .build()

  /**
   * The amount of time to wait in between unsuccessful index requests (in milliseconds).
   * 10 seconds = 10 * 1000 = 10000
   */
  private val BackoffPeriod = 10000L
  private val TstampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

  /**
   * Period between retrying sending events to S3
   *
   * @param sleepTime Length of time between tries
   */
  private def sleep(sleepTime: Long): Unit =
    try Thread.sleep(sleepTime)
    catch {
      case _: InterruptedException => ()
    }

  /**
   * Terminate the application
   *
   * Prevents shutdown hooks from running
   */
  private def forceShutdown(): Unit = {
    log.error(s"Shutting down application as unable to connect to S3 for over $maxConnectionTime ms")
    tracker foreach { t =>
      SnowplowTracking.trackApplicationShutdown(t)
      sleep(5000)
    }
    Runtime.getRuntime.halt(1)
  }

  /**
   * Returns an ISO valid timestamp
   *
   * @param tstamp The Timestamp to convert (milliseconds)
   * @return the formatted Timestamp
   */
  private def getTimestamp(tstamp: Long): String = {
    val dateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(tstamp), ZoneOffset.UTC)
    TstampFormat.format(dateTime)
  }

  /**
   * Sends records which fail deserialization, compression or partitioning
   *
   * @param records List of failed records
   */
  def sendFailures(records: java.util.List[EmitterInput]): Unit =
    for (Validated.Invalid(record) <- records.asScala) {
      log.warn(s"Record failed: ${record.line}")
      log.info("Sending failed record to Kinesis")
      val output = Json.obj(
          "line" -> record.line.asJson,
          "errors" -> record.errors.asJson,
          "failure_tstamp" -> getTimestamp(System.currentTimeMillis()).asJson
        )

      badSink.store(output.noSpaces, None, false)
    }

  /**
   * Keep attempting to send the data to S3 until it succeeds
   *
   * @param namedStream stream of rows with filename
   * @param bucket where data will be written
   * @return success status of sending to S3
   */
  def attemptEmit(
    namedStream: NamedStream,
    bucket: String,
    connectionAttemptStartTime: Long
  ): Boolean = {

    var attemptCount: Long = 1

    def logAndSleep(e: Throwable): Unit = {
      tracker.foreach { t =>
        SnowplowTracking.sendFailureEvent(t, BackoffPeriod, connectionAttemptStartTime, attemptCount, e.toString)
      }
      attemptCount = attemptCount + 1
      sleep(BackoffPeriod)

    }
    val connectionAttemptStartDateTime =
      ZonedDateTime.ofInstant(Instant.ofEpochMilli(connectionAttemptStartTime), ZoneOffset.UTC)
    while (true) {
      if (attemptCount > 1 && System.currentTimeMillis() - connectionAttemptStartTime > maxConnectionTime)
        forceShutdown()

      try {
        val outputStream = namedStream.stream

        val s3Key = DynamicPath.decorateDirectoryWithTime(namedStream.filename, connectionAttemptStartDateTime)

        val inputStream = new ByteArrayInputStream(outputStream.toByteArray)

        val objMeta = new ObjectMetadata()
        objMeta.setContentLength(outputStream.size.toLong)
        client.putObject(bucket, s3Key, inputStream, objMeta)

        return true
      } catch {
        // Retry on failure
        case e: AmazonServiceException =>
          log.error("S3 could not process the request", e)
          logAndSleep(e)
        case NonFatal(e) =>
          log.error("S3Emitter threw an unexpected exception", e)
          logAndSleep(e)
      }
    }
    false
  }
}
