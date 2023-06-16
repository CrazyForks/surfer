package inbounds


import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.*
import model.config.Inbound
import model.protocol.ConnectTo
import mu.KotlinLogging
import route.Route
import stream.RelayAndOutboundOp
import stream.relayAndOutbound
import java.net.URI


class HttpProxyServerHandler(private val inbound: Inbound) : ChannelInboundHandlerAdapter() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun channelRead(originCTX: ChannelHandlerContext, msg: Any) {
        //http proxy and http connect method
        if (msg is HttpRequest) {
            logger.info(
                "http inbound: [{}], method: {}, uri: {}",
                originCTX.channel().id().asShortText(),
                msg.method(),
                msg.uri()
            )
            if (msg.method() == HttpMethod.CONNECT) {
                tunnelProxy(originCTX, msg)
            } else {
                httpProxy(originCTX, msg)
            }
        } else {
            //other message
        }
    }

    private fun httpProxy(originCTX: ChannelHandlerContext, request: HttpRequest) {
        // If implement http capture, to code right here
        val uri = URI(request.uri())
        val resolveOutbound = Route.resolveOutbound(inbound)

        val port = when (uri.port) {
            -1 -> 80
            else -> uri.port
        }
        logger.trace("http proxy outbound from {}, content: {}", originCTX.channel().id().asShortText(), request)
        resolveOutbound.ifPresent { outbound ->
            val connectTo = ConnectTo(uri.host, port)
            val ch = EmbeddedChannel(HttpRequestEncoder())
            ch.writeOutbound(request)
            val encoded = ch.readOutbound<ByteBuf>()
            ch.close()
            relayAndOutbound(
                RelayAndOutboundOp(
                    originCTX = originCTX,
                    outbound = outbound,
                    connectTo = connectTo
                ).also { relayAndOutboundOp ->
                    relayAndOutboundOp.connectEstablishedCallback = {
                        it.writeAndFlush(encoded).also {
                            //remove all listener
                            val pipeline = originCTX.pipeline()
                            while (pipeline.first() != null) {
                                pipeline.removeFirst()
                            }
                        }
                    }
                    relayAndOutboundOp.connectFail = {
                        originCTX.close()
                    }
                }
            )
/*            when (outbound.protocol) {
                "galaxy" -> {
                    Surfer.relayAndOutbound(
                        originCTX = originCTX,
                        outbound = outbound,
                        connectEstablishedCallback = {
                            it.writeAndFlush(encoded).also {
                                //remove all listener
                                val pipeline = originCTX.pipeline()
                                while (pipeline.first() != null) {
                                    pipeline.removeFirst()
                                }
                            }
                        },
                        connectFail = {
                            originCTX.close()
                        },
                        connectTo = connectTo
                    )
                }

                "trojan" -> {
                    Surfer.relayAndOutbound(
                        originCTX = originCTX,
                        originCTXRelayHandler = {
                            TrojanRelayInboundHandler(
                                it, outbound, ConnectTo(uri.host, uri.port), true
                            )
                        },
                        outbound = outbound,
                        connectEstablishedCallback = {
                            it.writeAndFlush(encoded).also {
                                //remove all listener
                                val pipeline = originCTX.pipeline()
                                while (pipeline.first() != null) {
                                    pipeline.removeFirst()
                                }
                            }
                        },
                        connectFail = {
                            originCTX.close()
                        },
                        connectTo = connectTo
                    )
                }

                else -> {
                    logger.error(
                        "[${
                            originCTX.channel().id().asShortText()
                        }], protocol=${outbound.protocol} not support"
                    )
                }
            }*/
//            ReferenceCountUtil.release(encoded)
        }

    }

    private fun tunnelProxy(originCTX: ChannelHandlerContext, request: HttpRequest) {
        val uri = URI(
            if (request.uri().startsWith("https://")) {
                request.uri()
            } else {
                "https://${request.uri()}"
            }
        )
        val resolveOutbound = Route.resolveOutbound(inbound)

        val port = when (uri.port) {
            -1 -> 443
            else -> uri.port
        }
        resolveOutbound.ifPresent { outbound ->
            val connectTo = ConnectTo(uri.host, port)
            relayAndOutbound(
                RelayAndOutboundOp(
                    originCTX = originCTX,
                    outbound = outbound,
                    connectTo = connectTo
                ).also {relayAndOutboundOp ->
                    relayAndOutboundOp.connectEstablishedCallback = {
                        //write Connection Established
                        originCTX.writeAndFlush(
                            DefaultHttpResponse(
                                request.protocolVersion(),
                                HttpResponseStatus(HttpResponseStatus.OK.code(), "Connection established"),
                            )
                        ).also {
                            //remove all listener
                            val pipeline = originCTX.pipeline()
                            while (pipeline.first() != null) {
                                pipeline.removeFirst()
                            }
                        }
                    }
                    relayAndOutboundOp.connectFail = {
                        //todo: When the remote cannot be connected, the origin needs to be notified correctly
                        logger.warn { "from id: ${originCTX.channel().id().asShortText()}, connect to remote fail" }
                        originCTX.close()
                    }
                }
            )
           /* when (outbound.protocol) {
                "galaxy" -> {
                    Surfer.relayAndOutbound(
                        originCTX = originCTX,
                        outbound = outbound,
                        connectEstablishedCallback = {
                            //write Connection Established
                            originCTX.writeAndFlush(
                                DefaultHttpResponse(
                                    request.protocolVersion(),
                                    HttpResponseStatus(HttpResponseStatus.OK.code(), "Connection established"),
                                )
                            ).also {
                                //remove all listener
                                val pipeline = originCTX.pipeline()
                                while (pipeline.first() != null) {
                                    pipeline.removeFirst()
                                }
                            }
                        },
                        connectFail = {
                            //todo: When the remote cannot be connected, the origin needs to be notified correctly
                            logger.warn { "from id: ${originCTX.channel().id().asShortText()}, connect to remote fail" }
                            originCTX.close()
                        },
                        connectTo = connectTo
                    )
                }

                "trojan" -> {
                    Surfer.relayAndOutbound(
                        originCTX = originCTX,
                        originCTXRelayHandler = {
                            TrojanRelayInboundHandler(
                                it, outbound, ConnectTo(uri.host, uri.port), true
                            )
                        },

                        outbound = outbound,
                        connectEstablishedCallback = {
                            //write Connection Established
                            originCTX.writeAndFlush(
                                DefaultHttpResponse(
                                    request.protocolVersion(),
                                    HttpResponseStatus(HttpResponseStatus.OK.code(), "Connection established"),
                                )
                            ).also {
                                //remove all listener
                                val pipeline = originCTX.pipeline()
                                while (pipeline.first() != null) {
                                    pipeline.removeFirst()
                                }
                            }
                        },
                        connectFail = {
                            //todo: When the remote cannot be connected, the origin needs to be notified correctly
                            logger.warn { "from id: ${originCTX.channel().id().asShortText()}, connect to remote fail" }
                            originCTX.close()
                        },
                        connectTo = connectTo
                    )

                }

                else -> {
                    logger.error(
                        "id: ${
                            originCTX.channel().id().asShortText()
                        }, protocol=${outbound.protocol} not support"
                    )
                }
            }*/
        }
    }
}
