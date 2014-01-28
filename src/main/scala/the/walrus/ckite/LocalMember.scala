package the.walrus.ckite

import org.mapdb.DB
import the.walrus.ckite.rpc.RequestVote
import the.walrus.ckite.rpc.RequestVoteResponse
import the.walrus.ckite.states.Follower
import the.walrus.ckite.states.Leader
import the.walrus.ckite.states.Candidate
import the.walrus.ckite.rpc.Command
import the.walrus.ckite.states.State
import the.walrus.ckite.rpc.AppendEntriesResponse
import the.walrus.ckite.rpc.MajorityJointConsensus
import the.walrus.ckite.rpc.AppendEntries
import java.util.concurrent.atomic.AtomicReference
import the.walrus.ckite.states.Starter
import the.walrus.ckite.states.Stopped

class LocalMember(cluster: Cluster, binding: String, db: DB) extends Member(binding) {

  val state = new AtomicReference[State](Starter)
  val currentTerm = db.getAtomicInteger("term")
  val votedFor = db.getAtomicString("votedFor")

  def term(): Int = currentTerm.intValue()

  def voteForMyself = votedFor.set(id)

  def on(requestVote: RequestVote): RequestVoteResponse = cluster.synchronized {
    if (requestVote.term < term) {
      LOG.debug(s"Rejecting vote to old candidate: ${requestVote}")
      RequestVoteResponse(term, false)
    } else {
      currentState on requestVote
    }
  }

  def updateTermIfNeeded(receivedTerm: Int) = {
    if (receivedTerm > term) {
      LOG.debug(s"New term detected. Moving from ${term} to ${receivedTerm}.")
      votedFor.set("")
      currentTerm.set(receivedTerm)
      cluster.updateContextInfo
    }
  }

  def incrementTerm = {
    val term = currentTerm.incrementAndGet()
    cluster.updateContextInfo
    term
  }

  def becomeLeader(term: Int) = become(new Leader(cluster), term)

  def becomeCandidate(term: Int) = become(new Candidate(cluster), term)

  def becomeFollower(term: Int) = become(new Follower(cluster), term)

  private def become(newState: State, term: Int) = {
    if (currentState.canTransition) {
      LOG.info(s"Transition from $state to $newState")
      currentState stop

      changeState(newState)

      currentState begin term
    }
  }

  def on(appendEntries: AppendEntries): AppendEntriesResponse = currentState on appendEntries

  def on[T](command: Command): T = currentState.on[T](command)

  def on(jointConsensusCommited: MajorityJointConsensus) = currentState on jointConsensusCommited

  override def forwardCommand[T](command: Command): T = on(command)
  
  def currentState = state.get()

  private def changeState(newState: State) = state.set(newState)

  def stop = {
    become(Stopped, cluster.local.term)
  }
}