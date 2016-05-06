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
class DockerRiakCluster(config: RiakCluster) extends TestRule {

  val log = LoggerFactory.getLogger(classOf[DockerRiakCluster])
  val charset = Charset.defaultCharset().toString
  val docker = Docker()

  implicit val timeout: Timeout = Timeout(config.timeout)

  import docker.system
  import docker.system.dispatcher

  override def apply(base: Statement, description: Description): Statement = {
    new Statement {
      override def evaluate(): Unit = {
        // Extract baseName from either passed-in name, methodName, or testClass name
        val baseName = config.name match {
          case Some(n) => n
          case None => description.getMethodName match {
            case null => description.getTestClass.getSimpleName
            case m => m
          }
        }
        // Get primaryNode name from config or baseName + '1'
        val primaryNode = config.primaryNode match {
          case Some(n) => n
          case None => s"${baseName}1"
        }

        // Clean up existing containers
        cleanup(baseName)

        // Create and start the containers
        var containers: mutable.Buffer[(ContainerInfo, ActorRef)] = mutable.Buffer()
        for (i <- 1 to config.nodes) {
          val name = s"$baseName$i"
          val create = CreateContainer(
            Name = name,
            Hostname = Some(name),
            Tty = true,
            Image = config.image,
            Env = Some(Seq(s"CLUSTER1=$primaryNode")),
            Labels = Some(Map("baseName" -> baseName)),
            HostConfig = Some(HostConfig(PublishAllPorts = true))
          )
          Await.result(for {
            container <- docker.create(create)
            containerActor <- docker.start(container.Id)
            containerInfo <- docker.inspect(container.Id)
          } yield {
            containers += ((containerInfo, containerActor))
          }, timeout.duration)
        }
        log.debug("containers: {}", containers)

        // Wait for riak_kv to start in each container
        containers
          .foreach {
            case (ci, ref) => {
              Await.result(for {
                stdout <- ref ? Exec(Seq("riak-admin", "wait-for-service", "riak_kv"))
              } yield {
                (stdout match {
                  case bytes: ByteString => bytes.decodeString(charset).split("\n").filter(_.contains("riak_kv is up"))
                }).foreach(line => {
                  log.debug(line)
                })
              }, timeout.duration)
            }
          }

        // Discover primaryNode IP
        val primaryIpAddr = containers.find(_._1.Name.endsWith(primaryNode)) match {
          case Some((ci, ref)) => ci.NetworkSettings.Networks match {
            case Some(n) => n.values.head.IPAddress
            case None => "127.0.0.1"
          }
          case _ => throw new IllegalStateException(s"No primaryNode configured and no container named ${baseName}1 found")
        }

        // Join nodes to cluster
        containers
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

        // Wait for cluster to settle
        val settled = Promise[Boolean]()
        val checker = actor(new Act {
          become {
            case ex: Exec => (containers.head._2 ? ex) onSuccess {
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
        cleanup(baseName)
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
