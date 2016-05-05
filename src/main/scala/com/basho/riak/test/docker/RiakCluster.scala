package com.basho.riak.test.docker

import scala.concurrent.duration._

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
case class RiakCluster(name: Option[String] = None,
                       image:String="alpine",
                       nodes: Int = 1,
                       volumes: Seq[(String, String)] = Seq.empty,
                       timeout: FiniteDuration = 60 seconds,
                       statusTimeout:FiniteDuration = 10 seconds,
                       primaryNode:Option[String]=None) {

}
