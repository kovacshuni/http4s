package org.http4s
package server
package tomcat

import cats.effect.IO
import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets
import org.http4s.dsl.io._
import scala.io.Source
import org.apache.catalina.webresources.TomcatURLStreamHandlerFactory

class TomcatServerSpec extends {
  // Prevents us from loading jar and war URLs, but lets us
  // run Tomcat twice in the same JVM.  This makes me grumpy.
  //
  // Needs to run before the server is initialized in the superclass.
  // This also makes me grumpy.
  val _ = TomcatURLStreamHandlerFactory.disable()
} with Http4sSpec {
  def builder = TomcatBuilder[IO]

  val serverR =
    builder
      .bindAny()
      .mountService(
        HttpRoutes.of {
          case GET -> Root / "thread" / "routing" =>
            val thread = Thread.currentThread.getName
            Ok(thread)

          case GET -> Root / "thread" / "effect" =>
            IO(Thread.currentThread.getName).flatMap(Ok(_))

          case req @ POST -> Root / "echo" =>
            Ok(req.body)
        },
        "/"
      )
      .resource

  withResource(serverR) { server =>
    // This should be in IO and shifted but I'm tired of fighting this.
    def get(path: String): String =
      Source
        .fromURL(new URL(s"http://127.0.0.1:${server.address.getPort}$path"))
        .getLines
        .mkString

    // This too
    def post(path: String, body: String): String = {
      val url = new URL(s"http://127.0.0.1:${server.address.getPort}$path")
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      conn.setRequestMethod("POST")
      conn.setRequestProperty("Content-Length", bytes.size.toString)
      conn.setDoOutput(true)
      conn.getOutputStream.write(bytes)
      Source.fromInputStream(conn.getInputStream, StandardCharsets.UTF_8.name).getLines.mkString
    }

    "A server" should {
      "route requests on the service executor" in {
        get("/thread/routing") must startWith("http4s-spec-")
      }

      "execute the service task on the service executor" in {
        get("/thread/effect") must startWith("http4s-spec-")
      }

      "be able to echo its input" in {
        val input = """{ "Hello": "world" }"""
        post("/echo", input) must startWith(input)
      }
    }
  }
}
