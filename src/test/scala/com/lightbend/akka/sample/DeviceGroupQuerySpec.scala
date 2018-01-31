package com.lightbend.akka.sample

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.testkit.{TestKit, TestProbe}
import com.lightbend.akka.sample.DeviceGroup.TemperatureReading
import org.scalatest._

import scala.concurrent.duration.FiniteDuration

class DeviceGroupQuerySpec(_system: ActorSystem)
  extends TestKit(_system)
    with Matchers
    with FlatSpecLike
    with BeforeAndAfterAll {

  def this() = this(ActorSystem("DeviceManagerSpec"))

  override def afterAll: Unit = {
    shutdown(system)
  }

  def fixture = new {
    val requester = TestProbe()
    val device1 = TestProbe()
    val device2 = TestProbe()
    val queryActor = buildQueryActor(Map(device1.ref -> "device1", device2.ref -> "device2"), requester.ref, 3)
  }

  private def buildQueryActor(actors: Map[ActorRef, String], r: ActorRef, timeout: Int): ActorRef =
    system.actorOf(DeviceGroupQuery.props(
      actorToDeviceId = actors,
      requestId = 1,
      requester = r,
      timeout = FiniteDuration(timeout, TimeUnit.SECONDS)
    ))

  private def testResponse(tempMap: Map[String, TemperatureReading], t: TestProbe): Unit = {
    t.expectMsg(DeviceGroup.RespondAllTemperatures(
      requestId = 1,
      temperatures = tempMap
    ))
  }

  private def setTemp(actor: ActorRef, probe: TestProbe, temp: Option[Double]): Unit = {
    actor.tell(Device.RespondTemperature(0, temp), probe.ref)
  }

  val readTemp = Device.ReadTemperature(0)

  "a device group query" should "return temperature value for working devices" in {
    val f = fixture
    f.device1.expectMsg(readTemp)
    f.device2.expectMsg(readTemp)

    setTemp(f.queryActor, f.device1, Some(1.0))
    setTemp(f.queryActor, f.device2, Some(2.0))

    testResponse(Map("device1" -> DeviceGroup.Temperature(1.0), "device2" -> DeviceGroup.Temperature(2.0)), f.requester)
  }

  "a device group query" should "return TemperatureNotAvailable for devices with no readings" in {
    val f = fixture
    f.device1.expectMsg(readTemp)
    f.device2.expectMsg(readTemp)

    setTemp(f.queryActor, f.device1, None)
    setTemp(f.queryActor, f.device2, Some(2.0))

    testResponse(Map("device1" -> DeviceGroup.TemperatureNotAvailable, "device2" -> DeviceGroup.Temperature(2.0)), f.requester)
  }

  "a device group query" should "return DeviceNotAvailable if device stops before answering" in {
    val f = fixture
    f.device1.expectMsg(readTemp)
    f.device2.expectMsg(readTemp)

    setTemp(f.queryActor, f.device1, Some(1.0))
    f.device2.ref ! PoisonPill

    testResponse(Map("device1" -> DeviceGroup.Temperature(1.0), "device2" -> DeviceGroup.DeviceNotAvailable), f.requester)
  }

  "a device group query" should "return temperature reading even if device stops after answering" in {
    val f = fixture

    f.device1.expectMsg(readTemp)
    f.device2.expectMsg(readTemp)

    setTemp(f.queryActor, f.device1, Some(1.0))
    setTemp(f.queryActor, f.device2, Some(2.0))
    f.device2.ref ! PoisonPill

    testResponse(Map("device1" -> DeviceGroup.Temperature(1.0), "device2" -> DeviceGroup.Temperature(2.0)), f.requester)
  }

  "a device group query" should "return DeviceTimedOut if device does not answer in time" in {
    val f = fixture
    val queryActor = buildQueryActor(Map(f.device1.ref -> "device1", f.device2.ref -> "device2"), f.requester.ref, 1)

    f.device1.expectMsg(readTemp)
    f.device2.expectMsg(readTemp)

    setTemp(queryActor, f.device1, Some(1.0))

    testResponse(Map("device1" -> DeviceGroup.Temperature(1.0), "device2" -> DeviceGroup.DeviceTimedOut), f.requester)
  }
}
