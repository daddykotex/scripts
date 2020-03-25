import fs2.{io => fs2io, _}, fs2.concurrent._
import cats._, cats.implicits._, cats.effect._, cats.effect.implicits._, scala.concurrent.duration._

import io.circe._, io.circe.generic.semiauto._
import io.circe.fs2._

import org.http4s._
import org.http4s.headers._
import org.http4s.MediaType
import org.http4s.Method._
import org.http4s.client.blaze._
import org.http4s.client.dsl.io._
import org.http4s.client._
import org.http4s.Uri
import cats.effect.concurrent.Deferred

object PagedAPI extends IOApp {

  val baseUri = Uri.uri("https://gitlab.com/api/v4/projects/inkscape%2Finkscape/issues")
  val baseRequest = IO(System.getenv("GITLAB_TOKEN")).flatMap { token =>
    GET(
      baseUri,
      Header("PRIVATE-TOKEN", token),
      Accept(MediaType.application.json)
    )
  }

  def withClient[T](f: Client[IO] => IO[T]): IO[T] = {
    BlazeClientBuilder[IO](scala.concurrent.ExecutionContext.global)
    //.withMaxTotalConnections(1) with this, you'd normally hang after one page
    .resource.use { client =>
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

  def issuesStream(client: Client[IO])(uri: Uri): IO[(Stream[IO, GitlabIssue], IO[Option[Uri]])] = {
    val request = baseRequest.map(_.withUri(uri))

    Deferred[IO, Option[Uri]].map { promise =>
      val issues = Stream
        .eval(IO.delay(println(s"starting for ${uri.renderString}")))
        .evalMap(_ => request)
        .flatMap(client.stream)
        .flatMap { response =>
          val nextPage = getNextUri(response)
          val issues = response.body.through(byteArrayParser).through(decoder[IO, GitlabIssue])
          Stream.eval_(
            IO.delay(println(s"done for ${uri.renderString} and next page $nextPage")) *>
              promise.complete(nextPage)
          ) ++ issues
        }

      (issues, promise.get)
    }
  }

  def getIssues(client: Client[IO]): Stream[IO, GitlabIssue] =
    // Note that even though we're always lifting issuesStream from IO to Stream,
    // I kept the method as IO to make it explicit it doesn't carry any resources
    Stream.force {
      def go(page: Stream[IO, GitlabIssue], nextPageUri: IO[Option[Uri]]): Stream[IO, GitlabIssue] = {
        //response closes in this ++
        page ++
          Stream
            .eval(nextPageUri)
            .flatMap(_.foldMap(uri => Stream.eval(issuesStream(client)(uri)).flatMap((go _).tupled)))

      }

      issuesStream(client)(getRequestUri(Some("1"))).map((go _).tupled)
    }

  def run(args: List[String]): IO[ExitCode] =
    // If I take < 200, it work, but above that, it just hang
    withClient(
      getIssues(_)
        .take(201)
        .debug()
        .scan(0)((count, _) => count + 1)
        .showLinesStdOut
        .compile
        .drain
    ).as(ExitCode.Success)
}
