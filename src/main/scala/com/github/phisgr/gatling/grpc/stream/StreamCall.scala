package com.github.phisgr.gatling.grpc.stream

import com.github.phisgr.gatling.grpc.check.GrpcResponse.GrpcStreamEnd
import com.github.phisgr.gatling.grpc.check.{GrpcResponse, StreamCheck}
import com.github.phisgr.gatling.grpc.stream.StreamCall._
import com.github.phisgr.gatling.grpc.util.GrpcStringBuilder
import com.typesafe.scalalogging.StrictLogging
import io.gatling.commons.stats.{KO, OK}
import io.gatling.commons.util.StringHelper.Eol
import io.gatling.commons.util.Throwables.PimpedException
import io.gatling.commons.validation.{Failure, Success, Validation}
import io.gatling.core.action.Action
import io.gatling.core.check.Check
import io.gatling.core.session.Session
import io.gatling.core.stats.StatsEngine
import io.gatling.netty.util.StringBuilderPool
import io.grpc.{ClientCall, Metadata, Status}

import scala.util.control.{NoStackTrace, NonFatal}

abstract class StreamCall[Req, Res, State >: ServerStreamState](
  requestName: String,
  streamName: String,
  initState: State,
  protected var streamSession: Session,
  val call: ClientCall[Req, Res],
  timestampExtractor: TimestampExtractor[Res],
  combine: SessionCombiner,
  checks: List[StreamCheck[Res]],
  endChecks: List[StreamCheck[GrpcStreamEnd]],
  statsEngine: StatsEngine
) extends StrictLogging {

  protected def ignoreMessage: Boolean = (timestampExtractor eq TimestampExtractor.Ignore) && checks.isEmpty

  protected var state: State = initState
  protected var callStartTime: Long = _

  logger.info(s"Opening stream '$streamName': Scenario '${streamSession.scenario}', UserId #${streamSession.userId}")

  def onRes(res: Res, receiveTime: Long): Unit = {
    call.request(1)

    val (newSession, checkError) = Check.check(res, streamSession, checks, preparedCache = null)
    streamSession = if (checkError.isEmpty) newSession else newSession.markAsFailed

    val extractedTime = try {
      timestampExtractor.extractTimestamp(streamSession, res, callStartTime)
    } catch {
      case NonFatal(e) =>
        val message = s"Timestamp extraction crashed ${e.detailedMessage}"

        logger.warn(message)
        statsEngine.logResponse(
          streamSession.scenario,
          streamSession.groups,
          requestName = requestName,
          startTimestamp = receiveTime,
          endTimestamp = receiveTime,
          status = KO,
          responseCode = None,
          message = Some(message)
        )
        streamSession = streamSession.markAsFailed
        return
    }
    if (extractedTime == TimestampExtractor.IgnoreMessage) {
      return
    }

    statsEngine.logResponse(
      streamSession.scenario,
      streamSession.groups,
      requestName = requestName,
      startTimestamp = extractedTime,
      endTimestamp = receiveTime,
      status = if (checkError.isEmpty) OK else KO,
      responseCode = None,
      message = checkError.map(_.message)
    )
  }

  def onServerCompleted(grpcStatus: Status, trailers: Metadata, completeTimeMillis: Long): Unit = {
    state = Completed(grpcStatus, trailers)
    val (newSession, checkError) = Check.check(
      new GrpcResponse(null, grpcStatus, trailers),
      streamSession,
      endChecks,
      preparedCache = null
    )
    streamSession = newSession

    def dump = {
      StringBuilderPool.DEFAULT
        .get()
        .append(Eol)
        .appendWithEol(">>>>>>>>>>>>>>>>>>>>>>>>>>")
        .appendSession(streamSession)
        .appendWithEol("=========================")
        .appendWithEol("gRPC stream completion:")
        .appendStatus(grpcStatus)
        .appendTrailers(trailers)
        .append("<<<<<<<<<<<<<<<<<<<<<<<<<")
        .toString
    }

    val status = if (checkError.isEmpty) OK else KO
    val errorMessage = checkError.map(_.message)

    statsEngine.logResponse(
      streamSession.scenario,
      streamSession.groups,
      requestName = requestName,
      startTimestamp = completeTimeMillis,
      endTimestamp = completeTimeMillis,
      status = status,
      responseCode = Some(grpcStatus.getCode.toString),
      message = errorMessage
    )

    if (status == KO) {
      logger.info(s"Stream '$streamName' failed for user ${streamSession.userId}: ${errorMessage.getOrElse("")}")
      if (!logger.underlying.isTraceEnabled) {
        logger.debug(dump)
      }
    }
    logger.trace(dump)
  }

  def combineState(mainSession: Session, next: Action): Unit = {
    combineAndNext(mainSession, next, close = state.isInstanceOf[Completed])
  }
  def cancel(mainSession: Session, next: Action): Unit = {
    combineAndNext(mainSession, next, close = true)
    call.cancel(null, Cancelled)
  }

  private def combineAndNext(mainSession: Session, next: Action, close: Boolean): Unit = {
    val combined = try {
      combine.reconcile(main = mainSession, stream = streamSession)
    } catch {
      case NonFatal(e) =>
        logger.error("Session combining failed", e)
        mainSession
    }

    val newSession = if (!close) combined else combined.remove(streamName)
    next ! newSession
  }
}

object StreamCall {
  private[gatling] object Cancelled extends NoStackTrace

  sealed trait BidiStreamState
  sealed trait ServerStreamState extends BidiStreamState

  case object BothOpen extends BidiStreamState

  case object Receiving extends ServerStreamState

  case class Completed(status: Status, header: Metadata) extends ServerStreamState

  def ensureNoStream(session: Session, streamName: String, isBidi: Boolean): Validation[Unit] = {
    if (session.contains(streamName)) {
      Failure(s"Unable to create a new ${if (isBidi) "bidi" else "server"} stream with name $streamName: already exists")
    } else {
      Success(())
    }
  }
}
