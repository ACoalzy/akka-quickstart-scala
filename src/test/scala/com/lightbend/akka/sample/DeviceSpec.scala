package com.lightbend.akka.sample

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestKit, TestProbe}
import com.lightbend.akka.sample.Device.RespondTemperature
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, FunSuite, Matchers}

import scala.concurrent.duration.FiniteDuration

class DeviceSpec (_system: ActorSystem)
  extends TestKit(_system)
    with Matchers
    with FlatSpecLike
    with BeforeAndAfterAll {

  def this() = this(ActorSystem("DeviceSpec"))

  override def afterAll: Unit = {
    shutdown(system)
  }

  val fixture = new {
    val probe = TestProbe()
    val deviceActor = system.actorOf(Device.props("group", "device"))
    def read: Int => RespondTemperature = readTemp(deviceActor, probe)
    def record: (Int, Double) => Unit = recordTemp(deviceActor, probe)
  }

  private def readTemp(actor: ActorRef, probe: TestProbe)(id: Int): RespondTemperature = {
    actor.tell(Device.ReadTemperature(id), probe.ref)
    probe.expectMsgType[Device.RespondTemperature]
  }

  private def recordTemp(actor: ActorRef, probe: TestProbe)(id: Int, t: Double): Unit = {
    actor.tell(Device.RecordTemperature(id, t), probe.ref)
    probe.expectMsg(Device.TemperatureRecorded(id))
  }

  "a device actor" should "reply with empty reading if no temperature is known" in {
    val f = fixture
    val response = f.read(42)
    response.requestId should ===(42)
    response.value should ===(None)
  }

  "a device actor" should "reply with latest temperature reading" in {
    val f = fixture
    f.record(1, 24.0)
    val response1 = f.read(2)
    response1.requestId should ===(2)
    response1.value should ===(Some(24.0))

    f.record(3, 55.0)
    val response2 = f.read(4)
    response2.requestId should ===(4)
    response2.value should ===(Some(55.0))
  }

  "a device actor " should "reply to registration requests" in {
    val f = fixture
    f.deviceActor.tell(DeviceManager.RequestTrackDevice("group", "device"), f.probe.ref)
    f.probe.expectMsg(DeviceManager.DeviceRegistered)
    f.probe.lastSender should ===(f.deviceActor)
  }

  "a device actor " should "ignore wrong registration requests" in {
    val f = fixture
    f.deviceActor.tell(DeviceManager.RequestTrackDevice("wrongGroup", "device"), f.probe.ref)
    f.probe.expectNoMsg(FiniteDuration(500, TimeUnit.MILLISECONDS))

    f.deviceActor.tell(DeviceManager.RequestTrackDevice("group", "Wrongdevice"), f.probe.ref)
    f.probe.expectNoMsg(FiniteDuration(500, TimeUnit.MILLISECONDS))
  }
}
