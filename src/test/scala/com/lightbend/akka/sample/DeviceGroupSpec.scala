package com.lightbend.akka.sample

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration.FiniteDuration

class DeviceGroupSpec(_system: ActorSystem)
  extends TestKit(_system)
    with Matchers
    with FlatSpecLike
    with BeforeAndAfterAll {

  def this() = this(ActorSystem("DeviceGroupSpec"))

  override def afterAll: Unit = {
    shutdown(system)
  }

  def fixture = new {
    val probe = TestProbe()
    val groupActor = system.actorOf(DeviceGroup.props("group"))
    def reg: String => ActorRef = registerDevice(groupActor, probe)
    def has: Set[String] => Unit = hasList(groupActor, probe)
  }

  private def registerDevice(actor: ActorRef, probe: TestProbe)(device: String): ActorRef = {
    actor.tell(DeviceManager.RequestTrackDevice("group", device), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)
    probe.lastSender
  }

  private def isWorking(actor: ActorRef, probe: TestProbe, temp: Double = 1.0): Unit = {
    actor.tell(Device.RecordTemperature(requestId = 0, temp), probe.ref)
    probe.expectMsg(Device.TemperatureRecorded(requestId = 0))
  }

  private def hasList(actor: ActorRef, probe: TestProbe)(devices: Set[String]): Unit = {
    actor.tell(DeviceGroup.RequestDeviceList(0), probe.ref)
    probe.expectMsg(DeviceGroup.ReplyDeviceList(0, devices))
  }

  "a device group" should "be able to register a device actor" in {
    val f = fixture
    val deviceActor1 = f.reg("device1")
    val deviceActor2 = f.reg("device2")

    deviceActor1 should !==(deviceActor2)
    isWorking(deviceActor1, f.probe)
    isWorking(deviceActor2, f.probe)
  }

  "a device group" should "ignore requests for wrong groupId" in {
    val f = fixture
    f.groupActor.tell(DeviceManager.RequestTrackDevice("wrongGroup", "device1"), f.probe.ref)
    f.probe.expectNoMsg(FiniteDuration(500, TimeUnit.MILLISECONDS))
  }

  "a device group" should "return same actor for same deviceId" in {
    val f = fixture
    val deviceActor1 = f.reg("device1")
    val deviceActor2 = f.reg("device1")

    deviceActor1 should ===(deviceActor2)
  }

  "a device group" should "be able to list active devices" in {
    val f = fixture
    f.reg("device1")
    f.reg("device2")
    f.has(Set("device1", "device2"))
  }

  "a device group" should "be able to list active devices after one shuts down" in {
    val f = fixture
    val toShutDown = f.reg("device1")
    f.reg("device2")
    f.has(Set("device1", "device2"))

    f.probe.watch(toShutDown)
    toShutDown ! PoisonPill
    f.probe.expectTerminated(toShutDown)

    // using awaitAssert to retry because it might take longer for the groupActor
    // to see the Terminated, that order is undefined
    f.probe.awaitAssert(f.has(Set("device2")))
  }

  "a device group" should "be able to collect temperatures from all active devices" in {
    val f = fixture
    val deviceActor1 = f.reg("device1")
    val deviceActor2 = f.reg("device2")
    f.reg("device3")

    isWorking(deviceActor1, f.probe)
    isWorking(deviceActor2, f.probe, 2.0)

    f.groupActor.tell(DeviceGroup.RequestAllTemperatures(requestId = 0), f.probe.ref)
    val temperatures = Map(
      "device1" -> DeviceGroup.Temperature(1.0),
      "device2" -> DeviceGroup.Temperature(2.0),
      "device3" -> DeviceGroup.TemperatureNotAvailable)
    f.probe.expectMsg(DeviceGroup.RespondAllTemperatures(0, temperatures))
  }
}
