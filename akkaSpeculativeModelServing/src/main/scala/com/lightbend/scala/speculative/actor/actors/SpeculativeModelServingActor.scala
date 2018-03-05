package com.lightbend.scala.speculative.actor.actors

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout
import akka.pattern.ask
import akka.pattern.pipe

import com.lightbend.model.winerecord.WineRecord
import com.lightbend.scala.modelServer.model.ServingResult
import com.lightbend.scala.modelServer.model.speculative.{ServingRequest, ServingResponse, SpeculativeExecutionStats}
import com.lightbend.scala.speculative.actor.persistence.FilePersistence
import com.lightbend.scala.speculative.actor.processor.SimpleDesider

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration

// Speculative model server manager for a given data type

class SpeculativeModelServingActor(dataType : String, tmout : Long, models : List[ActorRef]) extends Actor {

  val ACTORTIMEOUT = new FiniteDuration(100, TimeUnit.MILLISECONDS)
  val SERVERTIMEOUT = 100l

  println(s"Creating speculative model serving actor $dataType")
  private val modelProcessors = models.to[ListBuffer]
  implicit var askTimeout = Timeout(if(tmout <= 0) tmout else  SERVERTIMEOUT, TimeUnit.MILLISECONDS)
  val decider = SimpleDesider

  var state = SpeculativeExecutionStats(dataType, decider.getClass.getName, askTimeout.duration.length, getModelsNames())

  override def preStart {
    val state = FilePersistence.restoreDataState(dataType)
    state._1.foreach(tmout => askTimeout = Timeout(if(tmout > 0) tmout else  SERVERTIMEOUT, TimeUnit.MILLISECONDS))
    state._2.foreach(models => {
      modelProcessors.clear()
      models.foreach(path => context.system.actorSelection(path).resolveOne(ACTORTIMEOUT).onComplete {
        case Success(ref) => modelProcessors += ref
        case _ =>
      }
    )})
  }

  override def receive = {
    // Model serving request
    case record : WineRecord =>
      val request = ServingRequest(UUID.randomUUID().toString, record)
      val zender = sender()
      val start = System.nanoTime()
      Future.sequence(
         modelProcessors.toList.map(ask(_,request).mapTo[ServingResponse]).map(f => f.map(Success(_)).recover({case e => Failure(e)})))
         .map(_.collect{ case Success(x) => x})
         .map(decider.decideResult(_)).mapTo[ServingResult]
         .map(servingResult => {
           if(servingResult.processed)
             state = state.incrementUsage(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), servingResult.actor)
           servingResult
         })
         .pipeTo(zender)
    // Current State request
    case request : GetSpeculativeServerState => sender() ! state
    // Configuration update
    case configuration : SetSpeculativeServer =>
      askTimeout = Timeout(if(configuration.tmout > 0) configuration.tmout else  SERVERTIMEOUT, TimeUnit.MILLISECONDS)
      modelProcessors.clear()
      modelProcessors ++= configuration.models
      state.updateConfig(askTimeout.duration.length, getModelsNames())
      FilePersistence.saveDataState(dataType, configuration.tmout, configuration.models)
      sender() ! "Done"
  }

  private def getModelsNames() : List[String] = modelProcessors.toList.map(_.path.name)
}

object SpeculativeModelServingActor{
  def props(dataType : String, tmout : Long, models : List[ActorRef]) : Props = Props(new SpeculativeModelServingActor(dataType, tmout, models))
}

case class SetSpeculativeServer(datatype : String, tmout : Long, models : List[ActorRef])

case class GetSpeculativeServerState(dataType : String)
