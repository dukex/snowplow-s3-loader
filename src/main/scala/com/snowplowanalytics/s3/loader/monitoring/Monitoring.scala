/*
 * Copyright (c) 2014-2021 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.s3.loader.monitoring

import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext

import cats.Id

import io.sentry.{Sentry, SentryClient, SentryOptions}

import com.snowplowanalytics.snowplow.scalatracker.Tracker

import com.snowplowanalytics.s3.loader.Config
import com.snowplowanalytics.s3.loader.processing.Batch.Meta

class Monitoring(snowplow: Option[Tracker[Id]], statsD: Option[Config.StatsD], sentry: Option[SentryClient]) {

  private implicit val EC: ExecutionContext =
    scala.concurrent.ExecutionContext.global

  def isSnowplowEnabled: Boolean =
    snowplow.isDefined

  def isStatsDEnabled: Boolean =
    statsD.isDefined

  def viaSnowplow(track: Tracker[Id] => Unit): Unit =
    snowplow.foreach(track)

  def report(meta: Meta): Unit =
    statsD.foreach { config =>
      StatsD.report(config)(meta).onComplete {
        case Success(_)     => ()
        case Failure(error) => System.err.println(error)
      }
    }

  /**
   * Send a startup event and attach a shutdown hook
   * No-op is Snowplow is not configured
   */
  def initTracking(): Unit =
    snowplow.foreach { tracker =>
      SnowplowTracking.initializeSnowplowTracking(tracker)
    }

  def captureError(error: Throwable): Unit =
    sentry.foreach { client =>
      client.sendException(error)
    }
}

object Monitoring {
  def build(config: Option[Config.Monitoring]): Monitoring =
    config match {
      case Some(Config.Monitoring(snowplow, sentry, metrics)) =>
        val tracker = snowplow.map { snowplowConfig =>
          SnowplowTracking.initializeTracker(snowplowConfig)
        }
        val sentryClient = sentry.map { sentryConfig =>
          val options = SentryOptions.defaults()
          options.setDsn(sentryConfig.dsn.toString)
          Sentry.init(options)
        }
        new Monitoring(tracker, metrics.flatMap(_.statsd), sentryClient)
      case None => new Monitoring(None, None, None)
    }
}
