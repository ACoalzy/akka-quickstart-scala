package com.lightbend.akka.sample

import akka.actor.{ActorSystem, PoisonPill, Terminated}
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

class DeviceManagerSpec (_system: ActorSystem)
  extends TestKit(_system)
    with Matchers
    with FlatSpecLike
    with BeforeAndAfterAll {

  def this() = this(ActorSystem("DeviceManagerSpec"))

  override def afterAll: Unit = {
    shutdown(system)
  }

  "a device manager" should "create a device group and device when receives tracking request for new group/device" in {
    val probe = TestProbe()
    val managerActor = system.actorOf(DeviceManager.props())

    managerActor.tell(DeviceManager.RequestTrackDevice("group1", "d1"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)
    val device1 = probe.lastSender

    // Check that the device actors are working
    device1.tell(Device.RecordTemperature(requestId = 0, 1.0), probe.ref)
    probe.expectMsg(Device.TemperatureRecorded(requestId = 0))
  }

  "a device manager" should "return same device group and device when receives tracking request for same group/device" in {
    val probe = TestProbe()
    val managerActor = system.actorOf(DeviceManager.props())

    managerActor.tell(DeviceManager.RequestTrackDevice("group1", "d1"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)
    val device1 = probe.lastSender

    managerActor.tell(DeviceManager.RequestTrackDevice("group1", "d1"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)
    val device2 = probe.lastSender

    assert(device1 == device2)

    // Check that the device actor is working
    device1.tell(Device.RecordTemperature(requestId = 0, 1.0), probe.ref)
    probe.expectMsg(Device.TemperatureRecorded(requestId = 0))
  }

  "a device manager" should "return different devices when receives tracking request for same group/device separated by termination" in {
    val probe = TestProbe()
    val managerActor = system.actorOf(DeviceManager.props())

    managerActor.tell(DeviceManager.RequestTrackDevice("group1", "d1"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)
    val device1 = probe.lastSender

    probe.watch(device1)
    device1 ! PoisonPill
    probe.expectTerminated(device1)

    managerActor.tell(DeviceManager.RequestTrackDevice("group1", "d1"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)
    val device2 = probe.lastSender

    assert(device1 != device2)

    // Check that the device actor is working
    device2.tell(Device.RecordTemperature(requestId = 0, 1.0), probe.ref)
    probe.expectMsg(Device.TemperatureRecorded(requestId = 0))
  }

  "a device manager" should "be able to return list of tracked groups" in {
    val probe = TestProbe()
    val managerActor = system.actorOf(DeviceManager.props())

    managerActor.tell(DeviceManager.RequestTrackDevice("group1", "d1"), probe.ref)
    managerActor.tell(DeviceManager.RequestTrackDevice("group2", "d1"), probe.ref)
    managerActor.tell(DeviceManager.RequestTrackDevice("group3", "d1"), probe.ref)

    managerActor.tell(DeviceManager.RequestGroupList(123), probe.ref)
    probe.expectMsg(DeviceManager.ReplyGroupList(123, Set("group1", "group2", "group3")))
  }

}
