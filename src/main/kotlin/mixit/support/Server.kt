package mixit.support

import org.springframework.beans.factory.InitializingBean
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.result.view.ViewResolver

import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import reactor.ipc.netty.NettyContext
import reactor.ipc.netty.http.server.HttpServer
import java.util.concurrent.atomic.AtomicReference
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansOfType
import org.springframework.web.reactive.function.server.ServerResponse

interface Server {
    fun start()
    fun stop()
    val isRunning: Boolean
}

class ReactorNettyServer(hostname: String, port: Int, val baseUri: String) : Server, ApplicationContextAware, InitializingBean {

    override val isRunning: Boolean
        get() {
            val context = this.nettyContext.get()
            return context != null && context.channel().isActive
        }

    private val server = HttpServer.create(hostname, port)
    private val nettyContext = AtomicReference<NettyContext>()
    lateinit private var appContext: ApplicationContext
    lateinit private var reactorHandler: ReactorHttpHandlerAdapter

    override fun setApplicationContext(context: ApplicationContext) {
        appContext = context
    }

    override fun afterPropertiesSet() {
        val routesProvider = appContext.getBeansOfType(RouterFunctionProvider::class).values
        val viewResolver = appContext.getBean(ViewResolver::class)
        val router = routesProvider.map { it.invoke() }.reduce(RouterFunction<ServerResponse>::and)
        val strategies = HandlerStrategies.builder()
                .viewResolver(viewResolver)
                .build()
        val webHandler = RouterFunctions.toHttpHandler(router, strategies)
        val httpHandler = WebHttpHandlerBuilder.webHandler(webHandler).filters(MixitWebFilter(baseUri)).build()
        reactorHandler = ReactorHttpHandlerAdapter(httpHandler)
    }

    override fun start() {
        if (!this.isRunning) {
            if (this.nettyContext.get() == null) {
                this.nettyContext.set(server.newHandler(reactorHandler)
                        .doOnNext { println("Reactor Netty server started on ${it.address()}") }
                        .block())
            }
        }
    }

    override fun stop() {
        if (this.isRunning) {
            val context = this.nettyContext.getAndSet(null)
            context.dispose()
            context.onClose()
        }
    }

}