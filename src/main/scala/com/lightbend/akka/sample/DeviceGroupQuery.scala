package com.lightbend.akka.sample

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import com.lightbend.akka.sample.DeviceGroup.TemperatureReading

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration

object DeviceGroupQuery {

  case object CollectionTimeout

  def props(actorToDeviceId: Map[ActorRef, String],
            requestId: Long,
            requester: ActorRef,
            timeout: FiniteDuration): Props = {
    Props(new DeviceGroupQuery(actorToDeviceId, requestId, requester, timeout))
  }
}

class DeviceGroupQuery(actorToDeviceId: Map[ActorRef, String],
                       requestId: Long,
                       requester: ActorRef,
                       timeout: FiniteDuration) extends Actor with ActorLogging {

  import DeviceGroupQuery._

  val queryTimeoutTimer = context.system.scheduler.scheduleOnce(timeout, self, CollectionTimeout)

  /**
    * Watch all actors and attempt to read temperatures
    */
  override def preStart(): Unit =
    actorToDeviceId.keysIterator.foreach { deviceActor =>
      context.watch(deviceActor)
      deviceActor ! Device.ReadTemperature(0)
    }

  override def postStop(): Unit = queryTimeoutTimer.cancel()

  override def receive: Receive = waitingForReplies(Map.empty, actorToDeviceId.keySet)

  /**
    * Handle messages received whilst waiting for temperature reads
    *
    * @param repliesSoFar
    * @param stillWaiting
    * @return
    */
  def waitingForReplies(repliesSoFar: Map[String, DeviceGroup.TemperatureReading], stillWaiting: Set[ActorRef]): Receive = {
    case Device.RespondTemperature(_, valueOption) =>
      val reading = valueOption match {
        case Some(value) => DeviceGroup.Temperature(value)
        case None => DeviceGroup.TemperatureNotAvailable
      }
      receivedResponse(sender(), reading, stillWaiting, repliesSoFar)

    case Terminated(deviceActor) =>
      receivedResponse(deviceActor, DeviceGroup.DeviceNotAvailable, stillWaiting, repliesSoFar)

    case CollectionTimeout =>
      val timedOutReplies = stillWaiting.map(deviceActor => actorToDeviceId(deviceActor) -> DeviceGroup.DeviceTimedOut)
      handleContext(repliesSoFar ++ timedOutReplies, Set())
  }

  /**
    * Stop watching actor, append reply to replies so far and handle context
    * @param deviceActor
    * @param reading
    * @param stillWaiting
    * @param repliesSoFar
    */
  def receivedResponse(deviceActor: ActorRef,
                       reading: DeviceGroup.TemperatureReading,
                       stillWaiting: Set[ActorRef],
                       repliesSoFar: Map[String, DeviceGroup.TemperatureReading]): Unit = {
    context.unwatch(deviceActor)
    val deviceId = actorToDeviceId(deviceActor)
    handleContext(repliesSoFar + (deviceId -> reading), stillWaiting - deviceActor)
  }

  /**
    * If not waiting on any actors then response all temperatures and stop, else update context with current replies
    * @param replies
    * @param stillWaiting
    */
  private def handleContext(replies: Map[String, TemperatureReading], stillWaiting: Set[ActorRef]): Unit = {
    if (stillWaiting.isEmpty) {
      requester ! DeviceGroup.RespondAllTemperatures(requestId, replies)
      context.stop(self)
    } else {
      context.become(waitingForReplies(replies, stillWaiting))
    }
  }

}