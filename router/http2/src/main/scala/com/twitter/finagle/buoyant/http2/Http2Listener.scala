package com.twitter.finagle.buoyant.http2

import com.twitter.finagle.netty4.Netty4Listener
import com.twitter.finagle.server.Listener
import io.netty.channel.{Channel, ChannelInitializer, ChannelPipeline}
import io.netty.handler.codec.http2.{Http2FrameCodec, Http2MultiplexCodec, Http2StreamFrame}

/**
 * Please note that the listener cannot be used for TLS yet.
 */
object Http2Listener {

  def mk(params: Stack.Params): Listener[Http2StreamFrame, Http2StreamFrame] = {
    /*
     * XXX The stream is configured with Netty4ServerChannelinitializer,
     * which expects that the inbound side of the pipeline transmits
     * bytes. However, the HTTP/2 listener uses frames and
     * de-multiplexes the connection earlier in the pipeline so that
     * each child stream transmits Http2StreamFrame objects.
     *
     * ChannelStatsHandler logs a tremendous amount of errors when
     * it processes non-ByteBuf messages, and so for now we just
     * remove it from each stream pipeline.
     */

    def initHttp2Connection(stream: ChannelInitializer[Channel]) =
      new ChannelInitializer[Channel] {
        def initChannel(ch: Channel): Unit = {
          // new TimingHandler(connStats.scope("outer")),

          ch.pipeline.addLast(new Http2FrameCodec(true /*server*/ ))

          // new DebugHandler("srv.conn"),
          // new Http2FrameStatsHandler(statsReceiver.scope("conn")),
          // new TimingHandler(connStats.scope("inner")),

          val child = prepChildStream(stream)
          val _ = ch.pipeline.addLast(new Http2MultiplexCodec(true /*server*/ , null, child))

          // No events happen on the pipeline after the muxer, since
          // it dispatches events onto the stream pipeline.
        }
      }

    def prepChildStream(stream: ChannelInitializer[Channel]) =
      new ChannelInitializer[Channel] {
        def initChannel(ch: Channel): Unit = {
          // ch.pipeline.addLast(new Http2FrameStatsHandler(statsReceiver.scope("stream")))
          ch.pipeline.addLast(stream)
          val _ = ch.pipeline.addLast(new ChannelInitializer[Channel] {
            def initChannel(ch: Channel): Unit = {
              val _ = ch.pipeline.remove("channel stats")
            }
          })
        }
      }

    // There's no need to configure anything on the stream channel,
    // but if we wanted to do install anything on each stream, this
    // would be where it happens.
    def initHttp2Stream(pipeline: ChannelPipeline): Unit = {}

    Netty4Listener(
      pipelineInit = initHttp2Stream,
      handlerDecorator = initHttp2Connection,
      // XXX Netty4's Http2 Codec doesn't support backpressure yet.
      // See https://github.com/netty/netty/issues/3667#issue-69640214
      params = params + Netty4Listener.BackPressure(false)
    )
  }
}
