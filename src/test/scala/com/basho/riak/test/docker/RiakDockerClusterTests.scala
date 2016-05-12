package com.basho.riak.test.docker

import org.junit.{ClassRule, Test}
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
class RiakDockerClusterTests {

  val log = LoggerFactory.getLogger(classOf[RiakDockerClusterTests])

  @Test
  def canCreateCluster(): Unit = {
    val hosts = RiakDockerClusterTests.cluster.containerHosts()
    log.debug("hosts: {}", hosts.mkString(","))
  }

}

object RiakDockerClusterTests {

  @ClassRule
  def cluster: DockerRiakCluster = DockerRiakCluster(RiakCluster(
    nodes = 3,
    timeout = 3 minutes,
    statusTimeout = 10 seconds,
    image = "basho/riak-ts"
  ))

}
