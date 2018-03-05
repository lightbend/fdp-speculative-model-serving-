package com.lightbend.scala.speculative.actor.distributed.actors

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, Props}
import com.lightbend.scala.modelServer.model.ServingResult
import com.lightbend.scala.modelServer.model.speculative.{ServingResponse, SpeculativeExecutionStats}
import com.lightbend.scala.speculative.actor.distributed.persistence.FilePersistence
import com.lightbend.scala.speculative.actor.distributed.processor.SimpleDesider

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

import scala.concurrent.ExecutionContext.Implicits.global

// Speculative model server manager for a given data type

class SpeculativeModelServingCollectorActor(dataType : String, tmout : Long, models : List[String]) extends Actor {

  val SERVERTIMEOUT = 100l

  println(s"Creating speculative model serving collector actor $dataType")

  val decider = SimpleDesider
  var timeout = new FiniteDuration(if(tmout > 0) tmout else  SERVERTIMEOUT, TimeUnit.MILLISECONDS)

  private val modelProcessors = models.to[ListBuffer]

  var state = SpeculativeExecutionStats(dataType, decider.getClass.getName, timeout.length, models)

  val currentProcessing = collection.mutable.Map[String, CurrentProcessing]()

  override def preStart {
    val state = FilePersistence.restoreDataState(dataType)
    state._1.foreach(tmout => new FiniteDuration(if(tmout > 0) tmout else  SERVERTIMEOUT, TimeUnit.MILLISECONDS))
    state._2.foreach(models => {
      modelProcessors.clear()
      modelProcessors ++= models
    })
  }

  override def receive = {
    // Start speculative requesr
    case start : StartSpeculative =>
//      println(s"Starting speculation ${start.GUID}")
      currentProcessing += (start.GUID -> CurrentProcessing(start.models, start.start, start.reply, new ListBuffer[ServingResponse]())) // Add to watch list
      context.system.scheduler.scheduleOnce(timeout, self, start.GUID)
    // Result of indivirual model serving
    case servingResponse : ServingResponse =>
//      println(s"Get response ${servingResponse.GUID}, result ${servingResponse.result}")
      currentProcessing.contains(servingResponse.GUID) match {
      case true =>
        val processingResults = currentProcessing(servingResponse.GUID)
        val current = CurrentProcessing(processingResults.models, processingResults.start, processingResults.reply, processingResults.results += servingResponse)
        current.results.size match {
          case size if (size >= current.models) => processResult(servingResponse.GUID, current)  // We are done
          case _ => currentProcessing += (servingResponse.GUID -> current)                       // Keep going
        }
      case _ => // should never happen
    }
    // Speculative execution completion
    case stop : String =>
//      println(s"Stopping speculation $stop")
      currentProcessing.contains(stop) match {
      case true => processResult(stop, currentProcessing(stop))
      case _ => // Its already done
    }
    // Current State request
    case request : GetSpeculativeServerState => sender() ! state
    // Configuration update
    case configuration : SetSpeculativeServerCollector =>
      timeout = new FiniteDuration(if(tmout > 0) tmout else  SERVERTIMEOUT, TimeUnit.MILLISECONDS)
      modelProcessors.clear()
      modelProcessors ++= configuration.models
      state.updateConfig(tmout, models)
      FilePersistence.saveDataState(dataType, configuration.tmout, configuration.models)
      sender() ! "Done"
  }

  // Complete speculative execution
  private def processResult(GUID : String, results: CurrentProcessing) : Unit = {
    val servingResult = decider.decideResult(results.results.toList).asInstanceOf[ServingResult]
    results.reply ! servingResult
    if(servingResult.processed)
      state = state.incrementUsage(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - results.start), servingResult.actor)
    currentProcessing -= GUID

  }
}

object SpeculativeModelServingCollectorActor{
  def props(dataType : String, tmout : Long, models : List[String]) : Props = Props(new SpeculativeModelServingCollectorActor(dataType, tmout, models))
}

case class StartSpeculative(GUID : String, start : Long, reply: ActorRef, models : Int)

case class CurrentProcessing(models : Int, start : Long, reply: ActorRef, results : ListBuffer[ServingResponse])

case class GetSpeculativeServerState(dataType : String)

case class SetSpeculativeServerCollector(datatype : String, tmout : Long, models : List[String])