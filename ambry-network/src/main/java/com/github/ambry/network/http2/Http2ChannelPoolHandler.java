/*
 * Copyright 2017 LinkedIn Corp. All rights reserved.
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

package com.github.ambry.network.http2;

import com.github.ambry.commons.SSLFactory;
import com.github.ambry.config.Http2ClientConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.pool.AbstractChannelPoolHandler;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.unix.Errors.NativeIoException;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * A {@link ChannelPoolHandler} to be used with {@link Http2ChannelPoolMap}.
 */
public class Http2ChannelPoolHandler extends AbstractChannelPoolHandler {
  private static final Logger logger = LoggerFactory.getLogger(Http2ChannelPoolHandler.class);
  private final SSLFactory sslFactory;
  private final String host;
  private final int port;
  private final Http2ClientConfig http2ClientConfig;
  private final ConnectionInboundExceptionHandler connectionInboundExceptionHandler;
  private final Http2ClientMetrics http2ClientMetrics;
  private final Http2PeerCertificateValidator http2PeerCertificateValidator;
  /**
   * Construct a {@link Http2ChannelPoolHandler}.
   * @param sslFactory the {@link SSLFactory} to use for generating {@link javax.net.ssl.SSLEngine} instances,
   *                   or {@code null} if SSL is not enabled in this pipeline.
   * @param http2ClientConfig the {@link Http2ClientConfig}
   * @param http2ClientMetrics the {@link Http2ClientMetrics}
   */
  public Http2ChannelPoolHandler(SSLFactory sslFactory, String host, int port, Http2ClientConfig http2ClientConfig,
      Http2ClientMetrics http2ClientMetrics) {
    this.sslFactory = sslFactory;
    this.host = host;
    this.port = port;
    this.http2ClientConfig = http2ClientConfig;
    this.connectionInboundExceptionHandler = new ConnectionInboundExceptionHandler();
    this.http2ClientMetrics = http2ClientMetrics;
    if (!http2ClientConfig.http2PeerCertificateSanRegex.trim().isEmpty()) {
      this.http2PeerCertificateValidator =
          new Http2PeerCertificateValidator(http2ClientConfig.http2PeerCertificateSanRegex, http2ClientMetrics);
    } else {
      this.http2PeerCertificateValidator = null;
    }
  }

  @Override
  public void channelCreated(Channel ch) throws Exception {
    ChannelPipeline pipeline = ch.pipeline();
    SslHandler sslHandler = new SslHandler(sslFactory.createSSLEngine(host, port, SSLFactory.Mode.CLIENT));
    pipeline.addLast(sslHandler);
    if (http2PeerCertificateValidator != null) {
      pipeline.addLast(http2PeerCertificateValidator);
    }
    pipeline.addLast(Http2FrameCodecBuilder.forClient()
        .initialSettings(Http2Settings.defaultSettings()
            .maxFrameSize(http2ClientConfig.http2FrameMaxSize)
            .initialWindowSize(http2ClientConfig.http2InitialWindowSize))
        .frameLogger(new Http2FrameLogger(LogLevel.DEBUG, "client"))
        .build());
    pipeline.addLast(new Http2MultiplexHandler(new ChannelInboundHandlerAdapter()));
    pipeline.addLast(connectionInboundExceptionHandler);
  }

  @ChannelHandler.Sharable
  private static class ConnectionInboundExceptionHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      if (cause instanceof Http2Exception.StreamException) {
        // This usually happens when server returns response for a request which was already dropped.
        logger.info("StreamException: {}", cause.getMessage());
      } else if (cause instanceof NativeIoException) {
        logger.info("Remote peer not available: " + cause.toString());
      } else {
        logger.warn("Connection inbound exception:", cause);
      }
    }
  }
}

