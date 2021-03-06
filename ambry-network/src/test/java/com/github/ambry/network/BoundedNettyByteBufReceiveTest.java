/**
 * Copyright 2019 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.network;

import com.github.ambry.utils.ByteBufferInputStream;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.Random;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;


public class BoundedNettyByteBufReceiveTest {

  private final NettyByteBufLeakHelper nettyByteBufLeakHelper = new NettyByteBufLeakHelper();

  @Before
  public void before() {
    nettyByteBufLeakHelper.beforeTest();
  }

  @After
  public void after() {
    nettyByteBufLeakHelper.afterTest();
  }

  /**
   * Test basic operation of {@link BoundedNettyByteBufReceive}.
   * @throws Exception
   */
  @Test
  public void testBoundedByteBufferReceive() throws Exception {
    int bufferSize = 2000;
    ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
    buffer.putLong(bufferSize);
    byte[] buf = new byte[bufferSize - Long.BYTES];
    new Random().nextBytes(buf);
    buffer.put(buf);
    buffer.flip();
    BoundedNettyByteBufReceive set = new BoundedNettyByteBufReceive(100000);
    Assert.assertEquals("Wrong number of bytes read", bufferSize,
        set.readFrom(Channels.newChannel(new ByteBufferInputStream(buffer))));
    buffer.clear();
    ByteBuf payload = set.content();
    for (int i = 8; i < bufferSize; i++) {
      Assert.assertEquals(buffer.array()[i], payload.readByte());
    }
    payload.release();
  }

  /**
   * Test when the request size is bigger than the maximum size
   * @throws Exception
   */
  @Test
  public void testBoundedByteBufferReceiveOnLargeRequest() throws Exception {
    int bufferSize = 2000;
    ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
    buffer.putLong(bufferSize);
    byte[] buf = new byte[bufferSize - Long.BYTES];
    new Random().nextBytes(buf);
    buffer.put(buf);
    buffer.flip();
    BoundedNettyByteBufReceive set = new BoundedNettyByteBufReceive(100);
    // The max request size is 100, but the buffer size is 2000, will result in IOException
    try {
      set.readFrom(Channels.newChannel(new ByteBufferInputStream(buffer)));
      Assert.fail("Should fail with IOException");
    } catch (IOException e) {
    }
    buffer.clear();
  }
}
