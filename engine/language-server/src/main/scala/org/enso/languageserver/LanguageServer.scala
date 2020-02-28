package org.enso.languageserver

import akka.actor.{Actor, ActorLogging, ActorRef, Stash}
import cats.effect.IO
import org.enso.languageserver.data._
import org.enso.languageserver.filemanager.FileManagerProtocol._
import org.enso.languageserver.filemanager.FileSystemObject
import org.enso.languageserver.filemanager.{
  FileSystemApi,
  FileSystemFailure,
  Path
}

object LanguageProtocol {

  /** Initializes the Language Server. */
  case object Initialize

  /**
    * Notifies the Language Server about a new client connecting.
    *
    * @param clientId the internal client id.
    * @param clientActor the actor this client is represented by.
    */
  case class Connect(clientId: Client.Id, clientActor: ActorRef)

  /**
    * Notifies the Language Server about a client disconnecting.
    * The client may not send any further messages after this one.
    *
    * @param clientId the id of the disconnecting client.
    */
  case class Disconnect(clientId: Client.Id)

  /**
    * Requests the Language Server grant a new capability to a client.
    *
    * @param clientId the client to grant the capability to.
    * @param registration the capability to grant.
    */
  case class AcquireCapability(
    clientId: Client.Id,
    registration: CapabilityRegistration
  )

  /**
    * Notifies the Language Server about a client releasing a capability.
    *
    * @param clientId the client releasing the capability.
    * @param capabilityId the capability being released.
    */
  case class ReleaseCapability(
    clientId: Client.Id,
    capabilityId: CapabilityRegistration.Id
  )

  /**
    * A notification sent by the Language Server, notifying a client about
    * a capability being taken away from them.
    *
    * @param capabilityId the capability being released.
    */
  case class CapabilityForceReleased(capabilityId: CapabilityRegistration.Id)

  /**
    * A notification sent by the Language Server, notifying a client about a new
    * capability being granted to them.
    *
    * @param registration the capability being granted.
    */
  case class CapabilityGranted(registration: CapabilityRegistration)

  /** Requests the language server to open a file on behalf of a given user.
    *
    * @param clientId the client opening the file.
    * @param path the file path.
    */
  case class OpenFile(clientId: Client.Id, path: Path)

  /** Sent by the server in response to [[OpenFile]]
    *
    * @param result either a file system failure, or successful opening data.
    */
  case class OpenFileResponse(result: Either[FileSystemFailure, OpenFileResult])

  /** The data carried by a successful file open operation.
    *
    * @param buffer file contents and current version.
    * @param writeCapability a write capability that could have been
    *                        automatically granted.
    */
  case class OpenFileResult(
    buffer: Buffer,
    writeCapability: Option[CapabilityRegistration]
  )

}

/**
  * An actor representing an instance of the Language Server.
  *
  * @param config the configuration used by this Language Server.
  */
class LanguageServer(config: Config, fs: FileSystemApi[IO])(
  implicit idGenerator: IdGenerator
) extends Actor
    with Stash
    with ActorLogging {
  import LanguageProtocol._

  override def receive: Receive = {
    case Initialize =>
      log.debug("Language Server initialized.")
      unstashAll()
      context.become(initialized(config))
    case _ => stash()
  }

  def initialized(
    config: Config,
    env: Environment = Environment.empty
  ): Receive = {
    case Connect(clientId, actor) =>
      log.debug("Client connected [{}].", clientId)
      context.become(
        initialized(config, env.addClient(Client(clientId, actor)))
      )

    case Disconnect(clientId) =>
      log.debug("Client disconnected [{}].", clientId)
      context.become(initialized(config, env.removeClient(clientId)))

    case AcquireCapability(
        clientId,
        reg @ CapabilityRegistration(_, capability: CanEdit)
        ) =>
      val (envWithoutCapability, releasingClients) = env.removeCapabilitiesBy {
        case CapabilityRegistration(_, CanEdit(file)) => file == capability.path
        case _                                        => false
      }
      releasingClients.foreach {
        case (client: Client, capabilities) =>
          capabilities.foreach { registration =>
            client.actor ! CapabilityForceReleased(registration.id)
          }
      }
      val newEnv = envWithoutCapability.grantCapability(clientId, reg)
      context.become(initialized(config, newEnv))

    case ReleaseCapability(clientId, capabilityId) =>
      context.become(
        initialized(config, env.releaseCapability(clientId, capabilityId))
      )

    case WriteFile(path, content) =>
      val result =
        for {
          rootPath <- config.findContentRoot(path.rootId)
          _        <- fs.write(path.toFile(rootPath), content).unsafeRunSync()
        } yield ()

      sender ! WriteFileResult(result)

    case ReadFile(path) =>
      val result =
        for {
          rootPath <- config.findContentRoot(path.rootId)
          content  <- fs.read(path.toFile(rootPath)).unsafeRunSync()
        } yield content

      sender ! ReadFileResult(result)

    case CreateFile(FileSystemObject.File(name, path)) =>
      val result =
        for {
          rootPath <- config.findContentRoot(path.rootId)
          _        <- fs.createFile(path.toFile(rootPath, name)).unsafeRunSync()
        } yield ()

      sender ! CreateFileResult(result)

    case CreateFile(FileSystemObject.Directory(name, path)) =>
      val result =
        for {
          rootPath <- config.findContentRoot(path.rootId)
          _        <- fs.createDirectory(path.toFile(rootPath, name)).unsafeRunSync()
        } yield ()

      sender ! CreateFileResult(result)

    case OpenFile(client, path) =>
      val existingBuffer = env.getFile(path)
      existingBuffer match {
        case Some(buffer) => addClientToBuffer(env, client, buffer, path)
        case None =>
          val fileContents = for {
            rootPath <- config.findContentRoot(path.rootId)
            content  <- fs.read(path.toFile(rootPath)).unsafeRunSync()
          } yield content
          fileContents
            .map { contents =>
              addClientToBuffer(env, client, OpenBuffer(contents), path)
            }
            .left
            .foreach { error =>
              sender ! OpenFileResponse(Left(error))
            }
      }
  }
  /* Note [Usage of unsafe methods]
     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     It invokes side-effecting function, all exceptions are caught and
     explicitly returned as left side of disjunction.
   */

  private def addClientToBuffer(
    env: Environment,
    client: Client.Id,
    buffer: OpenBuffer,
    path: Path
  ): Unit = {
    val envWithFileOpened =
      env.setFile(path, buffer.addClient(client))
    val (capability, newEnv) =
      envWithFileOpened.grantCanEditIfVacant(client, path)
    sender ! OpenFileResponse(
      Right(OpenFileResult(buffer.buffer, capability))
    )
    context.become(initialized(config, newEnv))
  }
}