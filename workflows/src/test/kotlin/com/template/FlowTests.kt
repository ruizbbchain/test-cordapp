
package com.template

import com.template.flows.Initiator
import com.template.flows.Responder
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.TestCordapp
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Duration


class FlowTests {
    
    private val network = MockNetwork(MockNetworkParameters(cordappsForAllNodes = listOf(
        TestCordapp.findCordapp("com.template.contracts"),
        TestCordapp.findCordapp("com.template.flows"),
        
        TestCordapp.findCordapp("com.r3.corda.lib.accounts.contracts"),
        TestCordapp.findCordapp("com.r3.corda.lib.accounts.workflows"),
        TestCordapp.findCordapp("com.r3.corda.lib.ci.workflows")
    )))
    private val a = network.createNode(CordaX500Name("PartyA", "London", "GB"))
    private val b = network.createNode(CordaX500Name("PartyB", "New York", "US"))
    
    init {
        listOf(a, b).forEach {
            it.registerInitiatedFlow(Responder::class.java)
        }
    }
    
    @Before
    fun setup() = network.runNetwork()
    
    @After
    fun tearDown() = network.stopNodes()
    
    @Test
    fun `dummy test`() {
        val result = a.startFlow(Initiator())
        network.runNetwork()
        result.getOrThrow(Duration.ofSeconds(1))
        assert(true)
    }
    
}
