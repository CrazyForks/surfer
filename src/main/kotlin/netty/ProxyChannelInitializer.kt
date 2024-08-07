package netty


import inbounds.ApiHandle
import inbounds.HttpProxyServerHandler
import inbounds.SocksServerHandler
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.HttpContentCompressor
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.handler.timeout.IdleStateHandler
import io.netty.handler.traffic.GlobalTrafficShapingHandler
import io.netty.util.concurrent.FutureListener
import io.netty.util.concurrent.Promise
import model.GLOBAL_TRAFFIC_SHAPING
import model.IDLE_CHECK_HANDLER
import model.IDLE_CLOSE_HANDLER
import model.LOG_HANDLER
import model.config.Inbound
import model.config.TlsInboundSetting
import model.config.WsInboundSetting
import model.protocol.Protocol
import io.github.oshai.kotlinlogging.KotlinLogging
import inbounds.TrojanInboundHandler
import netty.NettyServer.portInboundBinds
import stream.SslActiveHandler
import stream.WebSocketDuplexHandler
import utils.closeOnFlush
import java.io.File
import java.net.InetSocketAddress

private val logger = KotlinLogging.logger {}

class ProxyChannelInitializer : ChannelInitializer<NioSocketChannel>() {

    companion object {
        val globalTrafficShapingHandler = GlobalTrafficShapingHandler(NettyServer.workerGroup, 1000)
    }

    override fun initChannel(ch: NioSocketChannel) {

        val localAddress = ch.localAddress()

        val inbound = portInboundBinds.first {
            localAddress.port == (it.first.localAddress() as InetSocketAddress).port
        }.third
        ch.pipeline().addFirst(LOG_HANDLER, LoggingHandler(LogLevel.TRACE))
        //todo: set idle timeout, and close channel
        ch.pipeline().addFirst(IDLE_CLOSE_HANDLER, IdleCloseHandler())
        ch.pipeline().addFirst(IDLE_CHECK_HANDLER, IdleStateHandler(300, 300, 300))
        ch.pipeline().addFirst(GLOBAL_TRAFFIC_SHAPING, globalTrafficShapingHandler)
        when (Protocol.valueOfOrNull(inbound.protocol)) {
            Protocol.HTTP -> {
                initHttpInbound(ch, inbound)
            }

            Protocol.SOCKS5 -> {
                initSocksInbound(ch, inbound)
            }

            Protocol.TROJAN -> {
                initTrojanInbound(ch, inbound)
            }

            Protocol.API -> {
                initApiInbound(ch, inbound)
            }

            else -> {
                logger.error { "[${ch.id().asShortText()}] not support inbound: ${inbound.protocol}" }
                ch.closeOnFlush()
            }
        }
    }

    private fun initApiInbound(ch: NioSocketChannel, inbound: Inbound) {
        ch.pipeline().addLast(
            ChunkedWriteHandler(), HttpServerCodec(), HttpObjectAggregator(Int.MAX_VALUE)
        )
        ch.pipeline().addLast(ApiHandle(inbound))
    }

    private fun initSocksInbound(ch: NioSocketChannel, inbound: Inbound) {
        ch.pipeline().addLast(SocksPortUnificationServerHandler())
        ch.pipeline().addLast(SocksServerHandler(inbound))
    }

    private fun initHttpInbound(ch: NioSocketChannel, inbound: Inbound) {
        ch.pipeline().addLast(
            ChunkedWriteHandler(),
            HttpServerCodec(),
            HttpContentCompressor(),
            HttpObjectAggregator(Int.MAX_VALUE),
            HttpProxyServerHandler(inbound)
        )
    }

    private fun initTrojanInbound(ch: NioSocketChannel, inbound: Inbound) {
        when (Protocol.valueOfOrNull(inbound.inboundStreamBy!!.type)) {
            Protocol.WS -> {
                val handleShakePromise = trojanInboundAddPromise(ch, inbound)
                initWebsocketInbound(ch, inbound.inboundStreamBy.wsInboundSetting!!, handleShakePromise)
            }

            Protocol.TLS -> {
                val handleShakePromise = trojanInboundAddPromise(ch, inbound)
                initTlsInbound(ch, inbound.inboundStreamBy.tlsInboundSetting!!, handleShakePromise)
            }

            Protocol.TCP -> {
                // just add a trojan inbound handler is satisfied
                ch.pipeline().addLast(TrojanInboundHandler(inbound))
            }

            else -> {
                logger.error {
                    "[${
                        ch.id().asShortText()
                    }] not support inbound stream by: ${inbound.inboundStreamBy.type}"
                }
                ch.close()
            }
        }

    }

    private fun trojanInboundAddPromise(
        ch: NioSocketChannel, inbound: Inbound
    ): Promise<Channel> {
        val handleShakePromise = ch.eventLoop().next().newPromise<Channel>()
        handleShakePromise.addListener(FutureListener { future ->
            if (future.isSuccess) {
                future.get().pipeline().addLast(TrojanInboundHandler(inbound))
            }
        })
        return handleShakePromise
    }

    private fun initTlsInbound(
        ch: NioSocketChannel, tlsInboundSetting: TlsInboundSetting, handleShakePromise: Promise<Channel>
    ) {
        val sslCtx: SslContext = if (tlsInboundSetting.password != null) {
            SslContextBuilder.forServer(
                File(tlsInboundSetting.keyCertChainFile), File(tlsInboundSetting.keyFile), tlsInboundSetting.password
            ).build()
        } else {
            SslContextBuilder.forServer(File(tlsInboundSetting.keyCertChainFile), File(tlsInboundSetting.keyFile))
                .build()
        }
        ch.pipeline().addLast(
            sslCtx.newHandler(ch.alloc()), SslActiveHandler(handleShakePromise)
        )
    }

    private fun initWebsocketInbound(
        ch: NioSocketChannel, wsInboundSetting: WsInboundSetting, handleShakePromise: Promise<Channel>
    ) {
        ch.pipeline().addLast(
            ChunkedWriteHandler(),
            HttpServerCodec(),
            HttpObjectAggregator(Int.MAX_VALUE),
            WebSocketServerProtocolHandler(wsInboundSetting.path),
            WebSocketDuplexHandler(handleShakePromise)
        )
    }
}


