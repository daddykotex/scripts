import fs2.{io => fs2io, _}, fs2.concurrent._
import cats._, cats.implicits._, cats.effect._, cats.effect.implicits._, scala.concurrent.duration._

import io.circe._, io.circe.parser._, io.circe.generic.semiauto._
import io.circe.fs2._

import org.http4s._
import org.http4s.headers._
import org.http4s.MediaType
import org.http4s.Method._
import org.http4s.client.blaze._
import org.http4s.client.dsl.io._
import org.http4s.client._
import org.http4s.Uri

object PagedAPI extends IOApp {

  val baseUri = Uri.uri("https://gitlab.com/api/v4/projects/inkscape%2Finkscape/issues")
  val baseRequest = GET(
    baseUri,
    Header("PRIVATE-TOKEN", sys.env("GITLAB_TOKEN")),
    Accept(MediaType.application.json)
  )

  def withClient[T](f: Client[IO] => IO[T]): IO[T] = {
    BlazeClientBuilder[IO](scala.concurrent.ExecutionContext.global).resource.use { client =>
      f(client)
    }
  }

  def getRequestUri(maybePage: Option[String]): Uri = {
    maybePage.map(page => baseUri +? ("page", page)).getOrElse(baseUri)
  }

  def getNextUri(response: Response[IO]): Option[Uri] = {
    import org.http4s.util.CaseInsensitiveString

    response.headers
      .find(_.name === CaseInsensitiveString("X-Next-Page"))
      .map(h => getRequestUri(Some(h.value)))
  }

  def pagesStream(client: Client[IO]) = {
    Stream.unfoldLoopEval[IO, Uri, String](getRequestUri(None)) { uri =>
      val request = baseRequest.map(_.withUri(uri))
      client.fetch[(String, Option[Uri])](request) { response =>
        val nextUri = getNextUri(response)
        response.as[String].map(body => (body, nextUri))
      }
    }
  }

//there are many more fields, but let's ignore it for now
  final case class GitlabIssue(state: String, title: String)
  implicit val gitlabIssueDecoder: Decoder[GitlabIssue] = deriveDecoder[GitlabIssue]

  def issuesStream(client: Client[IO])(uri: Uri): Stream[IO, (Stream[IO, GitlabIssue], Option[Uri])] = {
    val request = baseRequest.map(_.withUri(uri))
    Stream
      .eval(IO.delay(println(s"starting for ${uri.renderString}")))
      .evalMap(_ => request)
      .flatMap(client.stream)
      .evalMap { response =>
        val nextPage = getNextUri(response)
        val issues = response.bodyAsText.through(stringArrayParser).through(decoder[IO, GitlabIssue])
        IO.delay(println(s"done for ${uri.renderString} and next page $nextPage"))
          .as((issues, nextPage))
      }
  }

  def getIssues(client: Client[IO]): Stream[IO, GitlabIssue] = {
    def go(onePage: (Stream[IO, GitlabIssue], Option[Uri])): Stream[IO, GitlabIssue] = {
      onePage match {
        case (issues, None) =>
          issues
        case (issues, Some(uri)) =>
          Stream(
            issues,
            issuesStream(client)(uri).flatMap(pair => go(pair))
          ).parJoinUnbounded
      }
    }
    issuesStream(client)(getRequestUri(Some("1"))).flatMap(go(_))
  }

  def run(args: List[String]): IO[ExitCode] =
    // If I take < 200, it work, but above that, it just hang
    withClient(
      getIssues(_)
        .take(201)
        .evalScan(0) { case (count, _) =>
          IO.delay(println(count)).as(count + 1)
        }
        .compile
        .drain
    ).as(ExitCode.Success)
}
