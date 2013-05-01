package org.rejna.fiesch
/*
import scala.collection.mutable.{ HashMap, HashSet, ArrayBuffer }
import scala.collection.JavaConversions._
import scala.io.Source
import scala.util.Random
import java.io.{ File, FileOutputStream, FileInputStream, ByteArrayInputStream, PrintWriter, RandomAccessFile }
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.{ Cipher, KeyGenerator, CipherOutputStream }
import javax.crypto.spec.IvParameterSpec
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.glacier.AmazonGlacierClient
import com.amazonaws.services.glacier.TreeHashGenerator
import com.amazonaws.services.glacier.model.InitiateMultipartUploadRequest
import com.amazonaws.services.glacier.model.UploadMultipartPartRequest
import com.amazonaws.services.glacier.model.CompleteMultipartUploadRequest
import com.amazonaws.services.glacier.transfer.ArchiveTransferManager
import com.amazonaws.util.BinaryUtils
import util.IsoUnit._
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter._
import sbinary._

object Config {
  val hashAlgorithm = "SHA"
  val bufferSize: Int = 1 mebi

  def blockSizeFor(fileSize: Long) = {
    if (fileSize < (1 mebi)) 1 kibi
    else if (fileSize < (512 mebi)) 512 kibi
    else if (fileSize < (1 gibi)) 1 mebi
    else if (fileSize < (512 gibi)) 512 mebi
    else 1 gibi
  }

  def awsCredentials = new BasicAWSCredentials("AKIAIR3XIFPGVNFBDPHA", "oXmmFkjZczHPTJ2bgli4EoKcoDvc9pvw3EkwndPc")

  val vaultName = "MyVault"

  val partSize = (1 mebi).toString

  val archiveSize = 50 mebi
  
  val fileFilter = new PrefixFileFilter("bk_")
  val fileLocation = "C:\\TEMP\\"

  val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
  val key = { 
    val kg = KeyGenerator.getInstance("AES"); // TODO
    kg.init(128)
    kg.generateKey()
  }
  val iv = Array.ofDim[Byte](16)
  Random.nextBytes(iv)
  val paramSpec: AlgorithmParameterSpec  = new IvParameterSpec(iv)
  cipher.init(Cipher.ENCRYPT_MODE, key, paramSpec);
  
  val workingDirectory = "C:\\TEMP"
}

object Serializer extends DefaultProtocol {
  import sbinary.DefaultProtocol._
  import sbinary.Operations._
  implicit val HashFormat = wrap[Hash, Array[Byte]](_.value, new Hash(_))
  implicit val ArchiveFormat = wrap[Archive, String](_.getArchiveId, new Archive(_))
  implicit val BlockLocationFormat = asProduct4(BlockLocation)(BlockLocation.unapply(_).get)
  
  implicit val FileUploaderFormat = asProduct4[FileUploader, String, Long, Long, Array[BlockLocation]](sys.error("not implemented"))(f => (f.filename, f.fileSize, f.blockSize, f.toArray))
  implicit val SnapshotUploaderFormat = wrap[SnapshotUploader, Array[FileUploader]](_.files.toArray, new SnapshotUploader(new SnapshotDownloader, _: _*))
  
  implicit val FileDownloaderFormat = asProduct4(FileDownloader)(FileDownloader.unapply(_).get)
  implicit val SnapshotDownloaderFormat = wrap[SnapshotDownloader, List[FileDownloader]](_.files, new SnapshotDownloader(_))
}

class SnapshotUploader(val previousSnapshot: SnapshotDownloader, initFiles: FileUploader*) {
  private val blocks = HashMap.empty[Hash, BlockLocation] ++ previousSnapshot.blocks.map(b => b.hash -> b)
  private val glacier = new Glacier
  val files = ArrayBuffer.empty[FileUploader] ++ initFiles

  def getOrCreateBlock(block: Block): BlockLocation = synchronized {
    blocks.getOrElseUpdate(block.hash, glacier.upload(block))
  }
  
  def addFile(file: File) = files += new FileUploader(this, file)
  
  def addFiles(fileList: Iterable[File]) = files ++= fileList.map(f => new FileUploader(this, f))
}

class FileUploader(val snapshot: SnapshotUploader, val file: File) extends IndexedSeq[BlockLocation] {
  lazy val hash = Hash(file) // XXX ?
  
  lazy val filename = file.getAbsolutePath

  lazy val fileSize = file.length

  lazy val blockSize = Config.blockSizeFor(fileSize)

  def apply(index: Int) = blocks(index)

  def length = blocks.length
  
  lazy val blocks = splitFile(file).map(b => snapshot.getOrCreateBlock(b)).toSeq

  private def splitFile(file: File) = {
    new Iterable[Block] {
      val fis = new FileInputStream(file)
      var pointer = 0
      def iterator = new Iterator[Block] {
        def hasNext = pointer < size
        def next = {
          val buffer = Array.ofDim[Byte]((size - pointer).min(blockSize))
          pointer += fis.read(buffer)
          Block(buffer)
        }
      }
    }
  }
}

class SnapshotDownloader(val files: List[FileDownloader] = List.empty[FileDownloader]) {
  lazy val blocks = files.flatMap(_.blocks)
  
  def download(glacier: Glacier, path: String, filter: (String, Long) => Boolean) = {
    val selectedFiles = files.filter(f => filter(f.filename, f.size))
    val archives = scala.collection.immutable.HashSet.empty[Archive] ++ selectedFiles.flatMap(_.blocks).map(_.archive)
    val archiveFiles = archives.par.map(a => a -> new RandomAccessFile(glacier.download(a), "r")).toMap
    for (f <- selectedFiles) {
      val fos = new FileOutputStream(path + File.pathSeparator + f.filename)
      for (b <- f.blocks) {
        var raf = archiveFiles(b.archive)
        raf.seek(b.offset)
        val buffer = Array.ofDim[Byte](b.size)
        fos.write(buffer)
      }
      fos.close()
    }
  }
  
  def load(glacier: Glacier, block: BlockLocation): Block = sys.error("not yet implemented") // TODO
}

case class FileDownloader(val filename: String, val size: Long, val hash: Hash, val blocks: Array[BlockLocation]) {
  def write(snapshot: SnapshotDownloader, path: String): Unit = {
    val fos = new FileOutputStream(path + File.pathSeparator + filename)
    //for (b <- blocks)
    //  fos.write(snapshot.load(b).data)
  }
}

case class BlockLocation(val hash: Hash, val archive: Archive, val offset: Long, val size: Int)

case class Block(val data: Array[Byte]) {
  lazy val hash = Hash(data)
  lazy val size = data.size
}

class Archive(var archiveId: Option[String]) {
  def this() = this(None)
  def this(_archiveId: String) = this(Some(_archiveId))
  def setId(id: String) = archiveId = Some(id)
  def getArchiveId: String = archiveId.getOrElse(sys.error("archiveId is not set"))
}

object Hash {
  def apply(file: File) = {
    val md = MessageDigest.getInstance(Config.hashAlgorithm)
    val buffer = Array.ofDim[Byte](Config.bufferSize)
    val fis = new FileInputStream(file)
    var nread = 0
    do {
      nread = fis.read(buffer)
      md.update(buffer, 0, nread)
    } while (nread == Config.bufferSize)
    fis.close
    new Hash(md.digest)
  }

  def apply(data: Array[Byte]) = {
    new Hash(MessageDigest.getInstance(Config.hashAlgorithm).digest(data))
  }
}

class Hash(val value: Array[Byte])

class Index {
  val glacier = new Glacier

  def backup: Unit = {
    val files = FileUtils.iterateFiles(new File(Config.fileLocation), Config.fileFilter, TrueFileFilter.INSTANCE)
    val snapshot = new SnapshotUploader(glacier.getLastSnapshot)
    for (f <- files)
      snapshot.addFile(f)
    val index = File.createTempFile("index_", "_glacier")
    Serializer.SnapshotUploaderFormat.writes(new FileOutputStream(index), snapshot)
    glacier.upload(index, "index")
  }

  def restore(where: String, filter: (String, Long) => Boolean): Unit = {
    val snapshot = glacier.getLastSnapshot
    val files = snapshot.files.filter(f => filter(f.filename, f.size))
    val blocks = files.flatMap(_.blocks)
    val archives = scala.collection.immutable.HashSet.empty[Archive] ++ blocks.map(_.archive)
    val archiveFiles = archives.par.map(a => a -> glacier.download(a)).toMap
    for (f <- files) {
      
    }
  }
  
  
}

class Uploader {
  val archive = new Archive
  val file = File.createTempFile("uploading_archive_", "_glacier")
  val output = new CipherOutputStream(
      new GZIPOutputStream(
          new FileOutputStream(
              file)), Config.cipher) 
  var _size = 0
  var closed = false
  
  def write(block: Block): BlockLocation = {
    if (closed)
      sys.error("This uploader is closed")
    output.write(block.data)
    val bl = new BlockLocation(block.hash, archive, size, block.size)
    _size += block.size
    bl
  }
  
  def upload(client: AmazonGlacierClient) = {
    closed = true
    output.close()
    val atm = new ArchiveTransferManager(client, Config.awsCredentials)
    val result = atm.upload(Config.vaultName, "data", file)
    archive.setId(result.getArchiveId)
    file.delete()
  }
  
  def size = _size
}

class Glacier {
  val client = new AmazonGlacierClient(Config.awsCredentials);
  client.setEndpoint("https://glacier.us-east-1.amazonaws.com/");

  var uploader = new Uploader
  
  def upload(block: Block): BlockLocation = {
    val bl = uploader.write(block)
    if (uploader.size >= Config.archiveSize) {
      uploader.upload(client)
      uploader = new Uploader
    }
    bl
  }
  
  def upload(file: File, description: String): Archive = {
    val archive = new Archive
    val atm = new ArchiveTransferManager(client, Config.awsCredentials)
    val result = atm.upload(Config.vaultName, description, file)
    archive.setId(result.getArchiveId)
    archive
  }
  
  def download(archive: Archive): File = {
    val archiveId = archive.archiveId.getOrElse(sys.error("Download fail : archiveId is not set"))
    val file = File.createTempFile(archiveId, ".glacier")
    new ArchiveTransferManager(client, Config.awsCredentials).download(Config.vaultName, archiveId, file)
    file
  }

  def getLastSnapshot = {
    new SnapshotDownloader // TODO
    //Serializer.SnapshotDownloaderFormat.reads(new FileInputStream(index))
  }
}
*/