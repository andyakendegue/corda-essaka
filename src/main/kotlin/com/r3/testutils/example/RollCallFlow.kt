package com.r3.testutils.example

import net.corda.v5.application.flows.*
import net.corda.v5.application.marshalling.JsonMarshallingService
import net.corda.v5.application.messaging.FlowMessaging
import net.corda.v5.application.messaging.FlowSession
import net.corda.v5.application.messaging.unwrap
import net.corda.v5.base.annotations.CordaSerializable
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.base.util.contextLogger

@InitiatingFlow("roll-call")
class RollCallFlow: RPCStartableFlow {

    private companion object {
        private const val RETRIES: Int = 2
        private val nl = System.lineSeparator()
        val log = contextLogger()
    }


    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @CordaInject
    lateinit var flowEngine: FlowEngine

    @Suspendable
    override fun call(requestBody: RPCRequestData): String {
        log.info("Flow invoked")

        val rollCall = requestBody.getRequestBodyAs(jsonMarshallingService, RollCallInitiationRequest::class.java)


        log.info("Initiating roll call")
        val sessionsAndRecipients = rollCall.recipientsX500.map {
            Pair(flowMessaging.initiateFlow(MemberX500Name.parse(it)), it)
        }

        log.info("Roll call initiated; waiting for responses")
        val teacherPrompt = flowEngine.virtualNodeName.commonName!!.uppercase()
        val responses = sessionsAndRecipients.map {
            Pair(
                it.first, it.first.sendAndReceive(
                    RollCallResponse::class.java,
                    RollCallRequest(it.second)
                ).unwrap { r -> r }.response
            )
        }
        val absenceResponses = responses.map { r ->
            val student = r.first.counterparty.commonName!!
            val response = r.second
            "$teacherPrompt: $student?" +
                    if (response.isEmpty()) {
                        retryRollCall(teacherPrompt, student, r)
                    } else {
                        nl + "${student.uppercase()}: $response"
                    }
        }

        return absenceResponses.joinToString(nl)
    }

    private fun retryRollCall(
        teacherPrompt: String,
        student: String,
        r: Pair<FlowSession, String>
    ) : String {
        var retries = 0
        var response = ""
        var script = ""
        while(retries < RETRIES && response.isEmpty()) {
            response = flowEngine.subFlow(AbsenceSubFlow(r.first.counterparty))
            script += nl + "$teacherPrompt: $student?" + response
            retries++
        }
        return script
    }

}

@InitiatingFlow("absence-call")
class AbsenceSubFlow(val counterparty: MemberX500Name) : SubFlow<String> {

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @CordaInject
    lateinit var flowEngine: FlowEngine

    override fun call(): String {
        val session = flowMessaging.initiateFlow(counterparty)
        session.send(RollCallRequest(counterparty.toString()))
        return session.receive(RollCallResponse::class.java).unwrap {it}.response
    }
}

@InitiatedBy("roll-call")
open class RollCallResponderFlow: ResponderFlow {

    @CordaInject
    lateinit var flowEngine: FlowEngine

    private companion object {
        val log = contextLogger()
    }

    @Suspendable
    override fun call(session: FlowSession) {
        log.info("Responder ${flowEngine.virtualNodeName} called")
        session.receive(RollCallRequest::class.java)

        log.info("Responder ${flowEngine.virtualNodeName} received request; sending response")
        val response = RollCallResponse(if(flowEngine.virtualNodeName.commonName == "Bueller") {
            ""
        } else {
            "Here!"
        })
        session.send(response)

        log.info("Responder ${flowEngine.virtualNodeName} sent response")
    }
}

@InitiatedBy("absence-call")
class AbsenceCallResponderFlow: RollCallResponderFlow()

@CordaSerializable
data class RollCallInitiationRequest(val recipientsX500: List<String>)
data class RollCallRequest(val recipientX500: String)
data class RollCallResponse(val response: String)