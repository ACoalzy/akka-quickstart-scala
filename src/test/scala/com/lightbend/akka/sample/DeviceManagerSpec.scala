package com.lightbend.akka.sample

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Terminated}
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

  def fixture = new {
    val probe = TestProbe()
    val managerActor = system.actorOf(DeviceManager.props())
    def reg: (String, String) => ActorRef = registerDevice(managerActor, probe)
  }

  private def registerDevice(actor: ActorRef, probe: TestProbe)(group: String, device: String): ActorRef = {
    actor.tell(DeviceManager.RequestTrackDevice(group, device), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)
    probe.lastSender
  }

  private def isWorking(actor: ActorRef, probe: TestProbe): Unit = {
    actor.tell(Device.RecordTemperature(requestId = 0, 1.0), probe.ref)
    probe.expectMsg(Device.TemperatureRecorded(requestId = 0))
  }

  "a device manager" should "create a device group and device when receives tracking request for new group/device" in {
    val f = fixture
    val device1 = f.reg("group1", "d1")

    isWorking(device1, f.probe)
  }

  "a device manager" should "return same device group and device when receives tracking request for same group/device" in {
    val f = fixture
    val device1 = f.reg("group1", "d1")
    val device2 = f.reg("group1", "d1")

    assert(device1 == device2)
    isWorking(device1, f.probe)
  }

  "a device manager" should "return different devices when receives tracking request for same group/device separated by termination" in {
    val f = fixture
    val device1 = f.reg("group1", "d1")

    f.probe.watch(device1)
    device1 ! PoisonPill
    f.probe.expectTerminated(device1)

    val device2 = f.reg("group1", "d1")
    assert(device1 != device2)

    isWorking(device2, f.probe)
  }

  "a device manager" should "be able to return list of tracked groups" in {
    val f = fixture
    f.reg("group1", "d1")
    f.reg("group2", "d1")
    f.reg("group3", "d1")

    f.managerActor.tell(DeviceManager.RequestGroupList(123), f.probe.ref)
    f.probe.expectMsg(DeviceManager.ReplyGroupList(123, Set("group1", "group2", "group3")))
  }

}
