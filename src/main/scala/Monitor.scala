import akka.actor.{Actor, ActorRef, Props}
// Monitor

// Modes of operation
sealed trait OperationMode

case object Debug extends OperationMode
case object Run extends OperationMode

val mode: String = "Run"

// Messages

case class BuildNetwork(numberOfAgents: Int)
case object StartNetwork
case class SendNetworksData(data: Map[String, NetworkData])

// Actor
class Monitor(operationMode: OperationMode, numOfNetworks: Int) extends Actor {
    var data: Map[String, NetworkData] = Map.empty
    var networks: Vector[ActorRef] = Vector.empty
    val DataSaver: ActorRef = context.actorOf(Props(new DataSaver(numOfNetworks)))
    var buildingTimer = new CustomTimer()
    var runningTimer = new CustomTimer()

    def receive: Receive = {
        case CreateNetwork(name, numberOfAgents, minNumberOfNeighbors, stopThreshold, degreeDistributionParameter,
        distribution) =>
            // Create a new Network actor
            val newNetwork = context.actorOf(Props(new Network(minNumberOfNeighbors, degreeDistributionParameter,
                stopThreshold, distribution, self)), name)

            // Add the new Network actor reference to the networks sequence
            networks = networks :+ newNetwork

            // Initial values for the NetworkData
            val initialReport = InitialReportData(Vector.empty, 0, 0.0, 0.0, Uniform)
            val roundReports = Vector.empty[RoundReportData]
            val finalReport = FinalReportData(0, Vector.empty)

            // Add the data entry for later monitoring
            data = data + (name -> NetworkData(initialReport, roundReports, finalReport))

            // Build the network
            buildingTimer.start()
            newNetwork ! BuildNetwork(numberOfAgents)


        case InitialReport(reportData) =>

            val updatedReportData = operationMode match {
                case Run => reportData.copy(AgentCharacteristics = Vector.empty)
                case Debug =>
                    //caseClassToString(reportData)
                    buildingTimer.stop(s"Network building took")
                    reportData
            }

            val network = sender()
            val networkName = network.path.name
            val existingData = data(networkName)
            val updatedData = existingData.copy(InitialReport = updatedReportData)

            data = data + (networkName -> updatedData)
            runningTimer.start()
            network ! StartNetwork


        case RoundReport(reportData) =>
            //caseClassToString(reportData)
            //println(reportData)
            operationMode match {
                case Debug =>
                    val networkName = sender().path.name
                    val existingData = data(networkName)
                    val updatedRoundReports = existingData.RoundReport :+ reportData
                    val updatedData = existingData.copy(RoundReport = updatedRoundReports)
                    data = data + (networkName -> updatedData)
                case Run =>

            }

        case FinalReport(reportData) =>
            //caseClassToString(reportData)
            val networkName = sender().path.name
            val existingData = data(networkName)

            operationMode match {
                case Debug =>
                    runningTimer.stop(s"Network was running for")
                    val updatedData = existingData.copy(FinalReport = reportData)
                    data = data + (networkName -> updatedData)
                    val agentCharacteristics = reportData.AgentCharacteristics

                    // 1. Calculate the mean confidence
                    val totalConfidence = agentCharacteristics.map(_.confidence).sum
                    val meanConfidence = totalConfidence / agentCharacteristics.size

                    // 2. Calculate the median confidence
                    val sortedConfidences = agentCharacteristics.map(_.confidence).sorted
                    val medianConfidence = if (agentCharacteristics.size % 2 == 0) {
                        (sortedConfidences(agentCharacteristics.size / 2 - 1) + sortedConfidences(agentCharacteristics.size / 2)) / 2.0
                    } else {
                        sortedConfidences(agentCharacteristics.size / 2)
                    }

                    // 3. Count agents based on their speaking status and belief
                    val speakingBelief0 = agentCharacteristics.count(agent => agent.speaking && agent.belief < 0.5)
                    val speakingBelief1 = agentCharacteristics.count(agent => agent.speaking && agent.belief >= 0.5)
                    val silentBelief0 = agentCharacteristics.count(agent => !agent.speaking && agent.belief < 0.5)
                    val silentBelief1 = agentCharacteristics.count(agent => !agent.speaking && agent.belief >= 0.5)
                    //caseClassToString(reportData)
                    println(s"Number of iterations: ${reportData.totalSteps}")
                    println(s"Mean Confidence: $meanConfidence")
                    println(s"Median Confidence: $medianConfidence")
                    println(s"Speaking with Belief < 0.5: $speakingBelief0")
                    println(s"Speaking with Belief >= 0.5: $speakingBelief1")
                    println(s"Silent with Belief < 0.5: $silentBelief0")
                    println(s"Silent with Belief>= 0.5: $silentBelief1")
                    //DataSaver ! SendNetworksData(data)
                case Run =>
                    //val updatedReportData = reportData.copy(AgentCharacteristics = Vector.empty)
                    val updatedData = existingData.copy(FinalReport = reportData)
                    data = data + (networkName -> updatedData)
                    DataSaver ! SendNetworksData(data)
            }
    }
}
