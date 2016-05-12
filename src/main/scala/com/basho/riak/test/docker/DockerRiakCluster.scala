package com.basho.riak.test.docker

import java.nio.charset.Charset

import akka.actor.ActorDSL._
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import com.jbrisbin.docker._
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success, Try}

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
class DockerRiakCluster(val config: RiakCluster,
                        val docker: Docker = Docker()) extends TestRule {

  private val log = LoggerFactory.getLogger(classOf[DockerRiakCluster])
  private val charset = Charset.defaultCharset().toString

  implicit val timeout: Timeout = Timeout(config.timeout)

  import docker.system.dispatcher
  import docker.{materializer, system}

  def containerHosts(): List[String] = {
    Await.result(
      for {
        nodes <- docker
          .containers(filters = Map("label" -> Seq("node=1")))
        stdout <- docker
          .exec(nodes.head.Id, Exec(Seq("riak-admin", "cluster", "status")))
          .runFold(mutable.Buffer[String]()) {
            case (buff, StdOut(bytes)) => {
              val validNodes = bytes.decodeString(charset)
                .split("\r\n")
                .toList
                .filter(_.contains("valid"))
                .map(line => {
                  line.split("\\|").toList match {
                    case _ :: addr :: _ => addr.substring(addr.indexOf('@') + 1).trim
                    case _ => "127.0.0.1"
                  }
                })
              log.debug("valid nodes: {}", validNodes)
              buff ++ validNodes
            }
          }
      } yield {
        stdout
      },
      timeout.duration
    ).toList
  }

  def stopRandomNode(): Unit = {

  }

  override def apply(base: Statement, description: Description): Statement = {
    // Extract baseName from either passed-in name, methodName, or testClass name
    val clusterName = config.name match {
      case Some(n) => n
      case None => description.getMethodName match {
        case null => description.getTestClass.getSimpleName
        case m => m
      }
    }
    // Get primaryNode name from config or baseName + '1'
    val primaryNode = config.primaryNode match {
      case Some(n) => n
      case None => s"${clusterName}1"
    }

    // Clean up existing containers
    cleanup(clusterName)

    // Create and start the containers
    var containerMeta: mutable.Buffer[(ContainerInfo, ActorRef)] = mutable.Buffer()
    for (i <- 1 to config.nodes) {
      val name = s"$clusterName$i"
      val create = CreateContainer(
        Name = name,
        Hostname = Some(name),
        Tty = true,
        Image = config.image,
        Labels = Some(Map("baseName" -> clusterName, "node" -> i.toString)),
        HostConfig = Some(HostConfig(PublishAllPorts = true))
      )
      Await.result(for {
        container <- docker.create(create)
        containerActor <- docker.start(container.Id)
        containerInfo <- docker.inspect(container.Id)
      } yield {
        containerMeta += ((containerInfo, containerActor))
      }, timeout.duration)
    }
    log.debug("containers: {}", containerMeta)

    // Wait for riak_kv to start in each container
    containerMeta
      .foreach {
        case (ci, ref) => {
          Await.result(
            for {
              stdout <- ref ? Exec(Seq("riak-admin", "wait-for-service", "riak_kv"))
            } yield {
              (stdout match {
                case bytes: ByteString => bytes.decodeString(charset).split("\n").filter(_.contains("riak_kv is up"))
              }).foreach(line => {
                log.debug(line)
              })
            },
            timeout.duration
          )
        }
      }

    // Discover primaryNode IP
    val primaryIpAddr = containerMeta.find(_._1.Name.endsWith(primaryNode)) match {
      case Some((ci, ref)) => ci.NetworkSettings.Networks match {
        case Some(n) => n.values.head.IPAddress
        case None => "127.0.0.1"
      }
      case _ => throw new IllegalStateException(s"No primaryNode configured and no container named ${clusterName}1 found")
    }

    // Join nodes to cluster
    containerMeta
      .filter {
        case (ci, ref) => !ci.Name.endsWith(primaryNode)
      }
      .foreach {
        case (ci, ref) => Await.result(
          for {
            join <- ref ? Exec(Seq("riak-admin", "cluster", "join", s"riak@$primaryIpAddr"))
            plan <- ref ? Exec(Seq("riak-admin", "cluster", "plan"))
            commit <- ref ? Exec(Seq("riak-admin", "cluster", "commit"))
            status <- ref ? Exec(Seq("riak-admin", "ring-status"))
          } yield {
            status match {
              case bytes: ByteString => log.debug(bytes.decodeString(charset))
            }
          },
          timeout.duration
        )
      }

    new Statement {
      override def evaluate(): Unit = {
        // Wait for cluster to settle
        val settled = Promise[Boolean]()
        val checker = actor(new Act {
          become {
            case ex: Exec => (containerMeta.head._2 ? ex) onSuccess {
              case bytes: ByteString =>
                bytes.decodeString(charset).split("\n").count(_.contains("Waiting on:")) match {
                  case 0 => {
                    settled.complete(Try(true))
                    context stop self
                  }
                  case _ => context.system.scheduler.scheduleOnce(config.statusTimeout, self, ex)
                }
            }
          }
        })
        checker ! Exec(Seq("riak-admin", "ring-status"))
        Await.result(settled.future, timeout.duration)

        // Run test
        Try(base.evaluate()) match {
          case Success(_) =>
          case Failure(ex) => log.error(ex.getMessage, ex)
        }

        // Cleanup after
        cleanup(clusterName)
      }
    }
  }

  private def cleanup(baseName: String) = {
    Await.result(
      docker
        .containers(all = true, filters = Map("label" -> Seq(s"baseName=$baseName")))
        .flatMap {
          case l if l.nonEmpty => docker.remove(Source(l), force = true)
          case _ => Future.successful(true)
        },
      timeout.duration
    )
  }

}

object DockerRiakCluster {
  def apply(): DockerRiakCluster = DockerRiakCluster(RiakCluster())

  def apply(config: RiakCluster): DockerRiakCluster = new DockerRiakCluster(config)
}

