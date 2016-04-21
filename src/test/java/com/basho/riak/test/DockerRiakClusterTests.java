/*
 * Copyright 2016 by the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.basho.riak.test;

import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.*;

/**
 * @author Jon Brisbin <jbrisbin@basho.com>
 */
public class DockerRiakClusterTests {

  @Test
  public void canCreateCluster() throws Throwable {
    DockerRiakCluster cluster = DockerRiakCluster.create().baseImage("basho/riak-ts").build();

    Statement stmt = mock(Statement.class);
    doNothing().when(stmt).evaluate();

    Description desc = mock(Description.class);
    doReturn("test").when(desc).getMethodName();

    cluster.apply(stmt, desc).evaluate();

    cluster.containerNames()
        .toList()
        .forEach(names -> assertThat("Containers were started", names, contains(equalTo("test1"))));
  }

}
