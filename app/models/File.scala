package models

import java.io.File

@Deprecated
class CryoFile(val name: String) {
  val file = new File(name)
}

@Deprecated
class LocalCryoFile(name: String) extends CryoFile(name) {
  
}

@Deprecated
class RemoteCryoFile(name: String, val blockLocations: List[BlockLocation]) extends CryoFile(name) {
  
}