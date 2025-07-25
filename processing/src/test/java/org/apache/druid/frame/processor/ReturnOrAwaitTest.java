/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.frame.processor;

import com.google.common.util.concurrent.Futures;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.hamcrest.CoreMatchers;
import org.hamcrest.MatcherAssert;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;

public class ReturnOrAwaitTest
{
  @Test
  public void testToString()
  {
    Assert.assertEquals("await channels=any{0, 1}", ReturnOrAwait.awaitAny(IntSet.of(0, 1)).toString());
    Assert.assertEquals("await channels=all{0, 1}", ReturnOrAwait.awaitAll(2).toString());
    Assert.assertEquals("return=xyzzy", ReturnOrAwait.returnObject("xyzzy").toString());

    MatcherAssert.assertThat(
        ReturnOrAwait.awaitAllFutures(Collections.singletonList(Futures.immediateFuture(1))).toString(),
        CoreMatchers.startsWith("await futures=[com.google.")
    );
  }
}
