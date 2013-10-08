package org.rejna.cryo.models

case object MakeActorReady

object MakeCryoContextReady {
  def apply(cryoctx: CryoContext) = {
    cryoctx.hashcatalog ! MakeActorReady
    cryoctx.deadLetterMonitor ! MakeActorReady
    cryoctx.datastore ! MakeActorReady
    cryoctx.notification ! MakeActorReady
    cryoctx.cryo ! MakeActorReady
    cryoctx.manager ! MakeActorReady
    cryoctx.inventory ! MakeActorReady
  }
}