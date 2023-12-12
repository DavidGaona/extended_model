import akka.actor.{Actor, ActorRef, ActorSystem, DeadLetter, Props}
import akka.util.Timeout
import akka.pattern.ask

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Random, Success}
import scala.concurrent.duration._
import scala.math.{log, E}

// Agent

// Messages
case class AddToNeighborhood(neighbor: ActorRef)

case class RequestOpinion(belief: Double)

case class SendOpinion(opinion: Int) // 0 = silent, 1 = agree, 2 disagree

case class SendAgentCharacteristics(agentData: AgentCharacteristicsItem)

case class ConfidenceUpdated(hasNextIter: Boolean, confidence: Double, opinion: Int)

def randomBetween(lower : Double = 0, upper : Double = 1): Double = {
    Random.nextDouble() * (upper - lower) + lower
}

// Actor
class Agent(stopThreshold: Double, distribution: Distribution) extends Actor {
    var belief: Double = -1
    val tol_radius: Double = randomBetween(0, 0.5)
    val tol_offset: Double = randomBetween(-tol_radius, tol_radius)
    var beliefExpressionThreshold: Double = -1
    var perceivedOpinionClimate: Double = 0.0
    var confidenceUnbounded: Double = -1
    var confidence: Double = -1
    var neighbors: Vector[ActorRef] = Vector.empty
    var firstIter: Boolean = true
    implicit val timeout: Timeout = Timeout(60.seconds)

    def calculateOpinionClimate(callback: Double => Unit): Unit = {
        val countsArr: Array[Int] = Array.fill(3)(0)

        // Create a sequence of futures for all neighbors
        val futures: Seq[Future[SendOpinion]] = neighbors.map { neighbor =>
            (neighbor ? RequestOpinion(belief)).mapTo[SendOpinion]
        }

        // Wait for all futures to complete
        Future.sequence(futures).onComplete {
            case Success(opinions) =>
                opinions.foreach { opinion =>
                    countsArr(opinion.opinion) += 1
                }
                val climate = countsArr(1) + countsArr(2) match {
                    case 0 => 0.0
                    case _ => (countsArr(1) - countsArr(2)).toDouble / (countsArr(1) + countsArr(2))
                }
                callback(climate)
            case Failure(exception) =>
                println(exception)
        }
    }

    def isCongruent(neighborBelief: Double): Boolean = {
        val lower = belief - tol_radius + tol_offset
        val upper = belief + tol_radius + tol_offset
        lower <= neighborBelief && neighborBelief <= upper
    }

    def receive: Receive = {
        case AddToNeighborhood(neighbor) =>
            neighbors = neighbors :+ neighbor

        case RequestOpinion(senderBelief) =>
            val agentSender = sender()
            if (confidence < beliefExpressionThreshold) agentSender ! SendOpinion(0)
            else if (isCongruent(senderBelief)) agentSender ! SendOpinion(1)
            else agentSender ! SendOpinion(2)

        case UpdateConfidence =>
            val network = sender()
            val oldConfidence = confidence
            calculateOpinionClimate { climate =>
                perceivedOpinionClimate = climate
                confidenceUnbounded = math.max(confidenceUnbounded + perceivedOpinionClimate, 0)
                confidence = (2 / (1 + Math.exp(-confidenceUnbounded))) - 1

                val aboveThreshold = math.abs(confidence - oldConfidence) >= stopThreshold || firstIter
                firstIter = false

                if (confidence >= beliefExpressionThreshold & belief < 0.5)
                    network ! ConfidenceUpdated(aboveThreshold, confidence, 0)
                else if (confidence >= beliefExpressionThreshold & belief >= 0.5)
                    network ! ConfidenceUpdated(aboveThreshold, confidence, 1)
                else if (confidence < beliefExpressionThreshold & belief < 0.5)
                    network ! ConfidenceUpdated(aboveThreshold, confidence, 2)
                else if (confidence < beliefExpressionThreshold & belief >= 0.5)
                    network ! ConfidenceUpdated(aboveThreshold, confidence, 3)
                else
                    println("Algo raro pasa mate")
            }

        case RequestAgentCharacteristics =>
            val network = sender()
            calculateOpinionClimate { climate =>
                perceivedOpinionClimate = climate
                val agentData = AgentCharacteristicsItem(
                    neighbors.size,
                    belief,
                    beliefExpressionThreshold,
                    confidence,
                    confidence >= beliefExpressionThreshold,
                    perceivedOpinionClimate
                )
                network ! SendAgentCharacteristics(agentData)
            }

    }

    override def preStart(): Unit = {
        distribution match {
            case Uniform =>
                //belief = if (Random.nextBoolean()) 1.0 else 0.0
                belief = randomBetween()

                def reverseConfidence(c: Double): Double = {
                    if (c == 1.0) {
                        37.42994775023705
                    } else {
                        -math.log(-((c - 1) / (c + 1)))
                    }
                }

                beliefExpressionThreshold = Random.nextDouble()
                //confidence = Random.nextDouble()
                //confidenceUnbounded = reverseConfidence(confidence)
                confidenceUnbounded = Random.nextDouble()
                confidence = (2 / (1 + Math.exp(-confidenceUnbounded))) - 1

            case Normal(mean, std) =>
            // ToDo Implement initialization for the Normal distribution

            case Exponential(lambda) =>
            // ToDo Implement initialization for the Exponential distribution
        }
    }
}
