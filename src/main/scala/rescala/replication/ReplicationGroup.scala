package rescala.extra.replication

import kofre.base.Lattice.Operators
import kofre.base.{Bottom, Lattice}
import kofre.dotted.Dotted
import kofre.syntax.DeltaBuffer
import loci.registry.{Binding, Registry}
import loci.transmitter.RemoteRef
import rescala.core.Disconnectable
import rescala.operator.Interface
import scribe.Execution.global

import scala.concurrent.Future
import scala.util.{Failure, Success}

case class DeltaFor[A](name: String, delta: Dotted[A])

class ReplicationGroup[Api <: Interface, A](
    val api: Api,
    registry: Registry,
    binding: Binding[DeltaFor[A] => Unit, DeltaFor[A] => Future[Unit]]
)(using
    dcl: Lattice[Dotted[A]],
    bottom: Bottom[Dotted[A]]
) {
  import api.*

  private var localListeners: Map[String, Evt[Dotted[A]]]    = Map.empty
  private var unhandled: Map[String, Map[String, Dotted[A]]] = Map.empty

  registry.bindSbj(binding) { (remoteRef: RemoteRef, payload: DeltaFor[A]) =>
    localListeners.get(payload.name) match {
      case Some(handler) => handler.fire(payload.delta)
      case None => unhandled = unhandled.updatedWith(payload.name) { current =>
          current merge Some(Map(remoteRef.toString -> payload.delta))
        }
    }
  }

  def distributeDeltaRDT(
      name: String,
      signal: Signal[DeltaBuffer[Dotted[A]]],
      deltaEvt: Evt[Dotted[A]],
  ): Unit = {
    require(!localListeners.contains(name), s"already registered a RDT with name $name")
    localListeners = localListeners.updated(name, deltaEvt)

    var observers    = Map[RemoteRef, Disconnectable]()
    var resendBuffer = Map[RemoteRef, Dotted[A]]()

    unhandled.get(name) match {
      case None =>
      case Some(changes) =>
        changes.foreach((_, v) => deltaEvt.fire(v))
    }

    def registerRemote(remoteRef: RemoteRef): Unit = {
      val remoteUpdate: DeltaFor[A] => Future[Unit] = registry.lookup(binding, remoteRef)

      def sendUpdate(delta: Dotted[A]): Unit = {
        val allToSend = (resendBuffer.get(remoteRef) merge Some(delta)).get
        resendBuffer = resendBuffer.removed(remoteRef)

        def scheduleForLater() = {
          resendBuffer = resendBuffer.updatedWith(remoteRef) { current =>
            current merge Some(allToSend)
          }
          // note, it might be prudent to actually schedule some task that tries again,
          // but for now we just remember the value and piggyback on sending whenever the next update happens,
          // which might be never ...
        }

        if (remoteRef.connected) {
          remoteUpdate(DeltaFor(name, allToSend)).onComplete {
            case Success(_) =>
            case Failure(_) => scheduleForLater()
          }
        } else {
          scheduleForLater()
        }
      }

      // Send full state to initialize remote
      val currentState = signal.readValueOnce.state
      if (currentState != bottom.empty) sendUpdate(currentState)

      // Whenever the crdt is changed propagate the delta
      // Praktisch wäre etwas wie crdt.observeDelta
      val observer = signal.observe { s =>
        val deltaStateList = s.deltaBuffer ++ resendBuffer.get(remoteRef).toList

        val combinedState = deltaStateList.reduceOption(Lattice[Dotted[A]].merge)

        combinedState foreach sendUpdate
      }
      observers += (remoteRef -> observer)
    }

    registry.remoteJoined.monitor(registerRemote)
    registry.remotes.foreach(registerRemote)
    registry.remoteLeft.monitor { remoteRef =>
      println(s"removing remote $remoteRef")
      observers(remoteRef).disconnect()
    }
    ()
  }
}
