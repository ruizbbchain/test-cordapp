
package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

import com.r3.corda.lib.accounts.workflows.accountService
import com.r3.corda.lib.accounts.workflows.flows.CreateAccount
import com.r3.corda.lib.accounts.workflows.flows.RequestKeyForAccount
import com.r3.corda.lib.accounts.workflows.flows.ShareStateAndSyncAccounts
import com.r3.corda.lib.ci.workflows.SyncKeyMappingInitiator
import com.template.contracts.TemplateContract
import com.template.states.TemplateState
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.ServiceHub
import java.io.FileInputStream
import java.nio.file.Paths
import java.util.*


fun getBNodeParty(serviceHub: ServiceHub): Party {
    return serviceHub.networkMapCache.getPeerByLegalName(CordaX500Name(
        organisation = "PartyB",
        locality = "New York",
        country = "US"
    )) ?: error("Couldn't find node")
}

val accountAName = "AccountA"
val accountBName = "AccountB"

// Node A
@InitiatingFlow
@StartableByRPC
class Initiator : FlowLogic<Unit>() {
    
    override val progressTracker = ProgressTracker()
    
    @Suspendable
    override fun call() {
        subFlow(CreateAccount(accountAName))
        
        val aAccountInfoStateAndRef = accountService.accountInfo(accountAName).single()
        val aAccountInfo = aAccountInfoStateAndRef.state.data
        val aAccountParty = subFlow(RequestKeyForAccount(aAccountInfo))
        
        val bParty = getBNodeParty(serviceHub)
        val bSession = initiateFlow(bParty)
        
        // Wait for account creation
        bSession.sendAndReceive<Boolean>(true)
        
//        accountService.shareAccountInfoWithParty(aAccountInfo.identifier.id, bParty).get()
        accountService.shareStateAndSyncAccounts(aAccountInfoStateAndRef, bParty).get()
//        subFlow(SyncKeyMappingInitiator(bParty, listOf(aAccountParty)))
//        subFlow(ShareStateAndSyncAccounts(aAccountInfoStateAndRef, bParty))
        
        bSession.sendAndReceive<Boolean>(true)
        
        val bAccountInfo = accountService.accountInfo(accountBName).single().state.data
        val bAccountParty = subFlow(RequestKeyForAccount(bAccountInfo))
        
//        subFlow(SyncKeyMappingInitiator(bParty, listOf(aAccountParty, bAccountParty)))
        
        val transactionBuilder = TransactionBuilder(notary = serviceHub.networkMapCache.notaryIdentities.first())
            .addOutputState(
                state = TemplateState(
                    data = "State",
                    participants = listOf(aAccountParty, bAccountParty)
                ),
                contract = TemplateContract::class.java.canonicalName
            )
            .addCommand(
                data = TemplateContract.Commands.Action(),
                keys = listOf(aAccountParty.owningKey, bAccountParty.owningKey)
            )
        
        val locallySignedTransaction = serviceHub.signInitialTransaction(transactionBuilder, listOf(aAccountParty.owningKey))
        val companySignature = subFlow(CollectSignatureFlow(locallySignedTransaction, bSession, bAccountParty.owningKey))
        val signedByCounterPartyTransaction = locallySignedTransaction.withAdditionalSignatures(companySignature)
        subFlow(FinalityFlow(signedByCounterPartyTransaction, listOf(bSession)))
    }
    
}

// Node B
@InitiatedBy(Initiator::class)
class Responder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {
    
    @Suspendable
    override fun call() {
        counterpartySession.receive<Boolean>()
        subFlow(CreateAccount(accountBName))
        counterpartySession.send(true)
        
        counterpartySession.receive<Boolean>()
        val bAccountInfoStateAndRef = accountService.accountInfo(accountBName).single()
        accountService.shareStateAndSyncAccounts(bAccountInfoStateAndRef, counterpartySession.counterparty).get()
        counterpartySession.send(true)
        
        val signedTransaction = subFlow(object : SignTransactionFlow(counterpartySession) {
            
            @Suspendable
            @Throws(FlowException::class)
            override fun checkTransaction(stx: SignedTransaction) {
                verifySigners(stx)
            }
            
        })
        
        subFlow(ReceiveFinalityFlow(
            otherSideSession = counterpartySession,
            expectedTxId = signedTransaction.id
        ))
    }
    
    @Suspendable
    private fun verifySigners(stx: SignedTransaction) {
        val signerAccounts = stx.tx.commands.single().signers
            .map { accountService.accountInfo(it)?.state?.data?: error("Couldn't find account info for signer: $it") }
        
        val currentSigners = signerAccounts.map { it.name }.toSet()
        val expectedSigners = setOf(accountAName, accountBName)
        
        requireThat {
            "Signers should have bankCode and companyCNPJ.\n" +
                "Expected: $expectedSigners\n" +
                "Got: $currentSigners" using
                (currentSigners == expectedSigners)
        }
    }
    
}
