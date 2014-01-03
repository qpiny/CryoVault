package org.rejna.cryo.models

import java.nio.file.Path
import java.util.{ UUID, Date }

import akka.event.Logging.Error
import akka.util.ByteString
import DataType._



/* Common */
case class Created(id: UUID)
case class Deleted(id: UUID)
case class ObjectList(date: Date, status: ObjectStatus, archives: List[DataStatus])
case class Done()
case class Uploaded(id: UUID)


case class NotFoundError(message: String, cause: Throwable = Error.NoCause) extends GenericError
case class OpenError(message: String, cause: Throwable = Error.NoCause) extends GenericError
case class WriteError(message: String, cause: Throwable = Error.NoCause) extends GenericError
case class ReadError(message: String, cause: Throwable = Error.NoCause) extends GenericError
case class DataNotFoundError(id: Either[UUID, String], message: String, cause: Throwable = Error.NoCause) extends GenericError
case class InvalidState(message: String, cause: Throwable = Error.NoCause) extends GenericError
case class DirectoryTraversalError(directory: String, cause: Throwable = Error.NoCause) extends GenericError // SnapshotError(s"Directory traversal attempt : ${directory}", cause)


/* Datastore */
case class CreateData(idOption: Option[UUID], dataType: DataType, size: Long = 0L) /* Created */
case class DefineData(id: UUID, glacierId: String, dataType: DataType, creationDate: Date, size: Long, checksum: String)
case class DataDefined(id: UUID)
case class DeleteData(id: UUID) /* => Deleted */
case class WriteData(id: UUID, position: Long, buffer: ByteString)
object WriteData { def apply(id: UUID, buffer: ByteString): WriteData = WriteData(id, -1, buffer) } // AppendData
case class DataWritten(id: UUID, position: Long, length: Long)

case class ReadData(id: UUID, position: Long, length: Int)
case class DataRead(id: UUID, position: Long, buffer: ByteString)

case class ClearLocalCache(id: UUID)
case class LocalCacheCleared(id: UUID)

case class PrepareDownload(id: UUID)
case class DownloadPrepared(id: UUID)

case class CloseData(id: UUID)
case class DataClosed(id: UUID)

case class PackData(id: UUID, glacierId: String)
case class DataPacked(id: UUID, glacierId: String)

case class GetDataStatus(id: Either[UUID, String])
object GetDataStatus {
  def apply(id: UUID) = new GetDataStatus(Left(id))
  def apply(glacierId: String) = new GetDataStatus(Right(glacierId))
}
case class DataStatus(id: UUID, dataType: DataType, creationDate: Date, status: ObjectStatus, size: Long, checksum: String)



/* Inventory */
case class CreateArchive()
case class CreateSnapshot()
case class DeleteSnapshot(id: UUID)
case class DeleteArchive(id: UUID)
case class GetArchiveList()
case class GetSnapshotList()

/* Glacier */
case class RefreshJobList()
case class RefreshInventory()
case class JobRequested(job: Job)
case class DownloadArchive(archiveId: String)
case class Upload(id: UUID, dataType: DataType)


/* Catalog */
case class ReserveBlock(block: Block)

case class AddBlock(block: Block, archiveId: UUID, offset: Long)
case class BlockAdded(blockId: Long)

//case class BlockLocationNotFound(hash: Hash) extends HashCatalogError("Blocklocation was not found")

case class GetCatalogContent(blockIds: Option[Set[Long]])
case class CatalogContent(catalog: List[BlockLocation])

/* Snapshot */
trait SnapshotMessage { val id: UUID }
case class UpdateFilter(id: UUID, file: String, filter: FileFilter)
case class GetFileList(id: UUID, path: String)
case class FileList(id: UUID, path: String, files: List[FileElement])
case class FileElement(path: Path, isFolder: Boolean, filter: Option[FileFilter], count: Int, size: Long)
case class GetFilter(id: UUID, path: String)
case class SnapshotFilter(id: UUID, path: String, filter: Option[FileFilter])
case class GetID()


/* Manager */
case class AddJob(job: Job)
//object AddJob { def apply(jobs: Job*): AddJobs = AddJobs(jobs.toList) }
case class JobAdded(job: Job)
case class RemoveJob(jobId: String)
//object RemoveJobs { def apply(jobIds: String*): RemoveJobs = RemoveJobs(jobIds.toList) }
case class JobRemoved(jobId: String)
case class UpdateJobList(jobs: List[Job])
case class JobListUpdated(jobs: List[Job])
case class GetJobList()
case class JobList(jobs: List[Job])
case class GetJob(jobId: String)
case class FinalizeJob(jobId: String)

/* Notification */
case class GetNotification()
case class GetNotificationARN()
case class NotificationARN(arn: String)
