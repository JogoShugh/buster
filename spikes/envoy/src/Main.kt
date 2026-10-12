package spike.envoy

import com.artie.buster.core.Engine
import com.artie.buster.core.MutationLog
import com.artie.buster.script.PluginHost
import com.sun.net.httpserver.HttpServer
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.net.InetSocketAddress
import java.nio.file.Path

private const val PROCESSOR_PORT = 9002
private const val UPSTREAM_PORT = 8081
private const val SEED = 42L

/**
 * Starts two things: a trivial upstream that answers JSON (stand-in for any server, in any
 * language), and Buster's ext_proc gRPC server. Envoy sits in front of the upstream.
 */
fun main(args: Array<String>) {
    val plugins = Path.of(args.getOrElse(0) { "plugins" })
    val report = PluginHost(plugins).reload()
    report.failures.forEach { println("plugin script ${it.file} failed: ${it.messages.joinToString("; ")}") }
    println("plugins ${report.plugins.joinToString { it.name }}")
    val engine = Engine(report.plugins, SEED, MutationLog(null))

    upstream().start()
    val server = NettyServerBuilder.forPort(PROCESSOR_PORT).addService(ExtProc(engine)).build().start()
    println("upstream :$UPSTREAM_PORT, ext_proc :$PROCESSOR_PORT")
    server.awaitTermination()
}

private fun upstream(): HttpServer {
    val http = HttpServer.create(InetSocketAddress(UPSTREAM_PORT), 0)
    http.createContext("/") { exchange ->
        val body = """{"hello":"from upstream","path":"${exchange.requestURI.path}"}""".toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
    return http
}
