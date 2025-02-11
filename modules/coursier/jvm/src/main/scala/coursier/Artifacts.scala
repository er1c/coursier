package coursier

import java.io.File
import java.lang.{Boolean => JBoolean}

import coursier.cache.{ArtifactError, Cache}
import coursier.error.FetchError
import coursier.util.{Sync, Task}

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

final class Artifacts[F[_]] private[coursier] (private val params: Artifacts.Params[F]) {

  override def equals(obj: Any): Boolean =
    obj match {
      case other: Artifacts[_] =>
        params == other.params
    }

  override def hashCode(): Int =
    17 + params.##

  override def toString: String =
    s"Artifacts($params)"

  private def withParams(params: Artifacts.Params[F]): Artifacts[F] =
    new Artifacts(params)

  def resolutions: Seq[Resolution] =
    params.resolutions
  def classifiers: Set[Classifier] =
    params.classifiers
  def mainArtifactsOpt: Option[Boolean] =
    params.mainArtifactsOpt
  def artifactTypesOpt: Option[Set[Type]] =
    params.artifactTypesOpt
  def cache: Cache[F] =
    params.cache
  def transformArtifactsOpt: Option[Seq[Artifact] => Seq[Artifact]] =
    params.transformArtifactsOpt
  def S: Sync[F] =
    params.S

  def withResolution(resolution: Resolution): Artifacts[F] =
    withParams(params.copy(resolutions = Seq(resolution)))
  def withResolutions(resolutions: Seq[Resolution]): Artifacts[F] =
    withParams(params.copy(resolutions = resolutions))
  def withClassifiers(classifiers: Set[Classifier]): Artifacts[F] =
    withParams(params.copy(classifiers = classifiers))
  def withMainArtifacts(mainArtifacts: JBoolean): Artifacts[F] =
    withParams(params.copy(mainArtifactsOpt = Option(mainArtifacts).map(x => x)))
  def withArtifactTypes(artifactTypes: Set[Type]): Artifacts[F] =
    withParams(params.copy(artifactTypesOpt = Option(artifactTypes)))
  def withCache(cache: Cache[F]): Artifacts[F] =
    withParams(params.copy(cache = cache))

  def transformArtifacts(f: Seq[Artifact] => Seq[Artifact]): Artifacts[F] =
    withParams(params.copy(transformArtifactsOpt = Some(params.transformArtifactsOpt.fold(f)(_ andThen f))))
  def noTransformArtifacts(): Artifacts[F] =
    withParams(params.copy(transformArtifactsOpt = None))
  def withTransformArtifacts(fOpt: Option[Seq[Artifact] => Seq[Artifact]]): Artifacts[F] =
    withParams(params.copy(transformArtifactsOpt = fOpt))

  def io: F[Seq[(Artifact, File)]] = {

    val a = params
      .resolutions
      .flatMap { r =>
        Artifacts.artifacts0(
          r,
          params.classifiers,
          params.mainArtifactsOpt,
          params.artifactTypesOpt
        ).map(_._3)
      }
      .distinct

    Artifacts.fetchArtifacts(
      params.transformArtifacts(a),
      params.cache
    )(S)
  }

}

object Artifacts {

  // see Resolve.apply for why cache is passed here
  def apply[F[_]](cache: Cache[F] = Cache.default)(implicit S: Sync[F]): Artifacts[F] =
    new Artifacts(
      Params(
        Nil,
        Set(),
        None,
        None,
        cache,
        None,
        S
      )
    )

  implicit class ArtifactsTaskOps(private val artifacts: Artifacts[Task]) extends AnyVal {

    def future()(implicit ec: ExecutionContext = artifacts.params.cache.ec): Future[Seq[(Artifact, File)]] =
      artifacts.io.future()

    def either()(implicit ec: ExecutionContext = artifacts.params.cache.ec): Either[FetchError, Seq[(Artifact, File)]] = {

      val f = artifacts
        .io
        .map(Right(_))
        .handle { case ex: FetchError => Left(ex) }
        .future()

      Await.result(f, Duration.Inf)
    }

    def run()(implicit ec: ExecutionContext = artifacts.params.cache.ec): Seq[(Artifact, File)] = {
      val f = artifacts.io.future()
      Await.result(f, Duration.Inf)
    }

  }

  private[coursier] final case class Params[F[_]](
    resolutions: Seq[Resolution],
    classifiers: Set[Classifier],
    mainArtifactsOpt: Option[Boolean],
    artifactTypesOpt: Option[Set[Type]],
    cache: Cache[F],
    transformArtifactsOpt: Option[Seq[Artifact] => Seq[Artifact]],
    S: Sync[F]
  ) {
    def transformArtifacts: Seq[Artifact] => Seq[Artifact] =
      transformArtifactsOpt.getOrElse(identity[Seq[Artifact]])

    override def toString: String =
      productIterator.mkString("ArtifactsParams(", ", ", ")")
  }

  def defaultTypes(
    classifiers: Set[Classifier] = Set.empty,
    mainArtifactsOpt: Option[Boolean] = None
  ): Set[Type] = {

    val mainArtifacts0 = mainArtifactsOpt.getOrElse(classifiers.isEmpty)

    val fromMainArtifacts =
      if (mainArtifacts0)
        Set[Type](Type.jar, Type.testJar, Type.bundle)
      else
        Set.empty[Type]

    val fromClassifiers = classifiers.flatMap {
      case Classifier.sources => Set(Type.source)
      case Classifier.javadoc => Set(Type.doc)
      case _ => Set.empty[Type]
    }

    fromMainArtifacts ++ fromClassifiers
  }


  private[coursier] def artifacts0(
    resolution: Resolution,
    classifiers: Set[Classifier],
    mainArtifactsOpt: Option[Boolean],
    artifactTypesOpt: Option[Set[Type]]
  ): Seq[(Dependency, Attributes, Artifact)] = {

    val mainArtifacts0 = mainArtifactsOpt.getOrElse(classifiers.isEmpty)

    val artifactTypes0 =
      artifactTypesOpt
        .getOrElse(defaultTypes(classifiers, mainArtifactsOpt))

    val main =
      if (mainArtifacts0)
        resolution.dependencyArtifacts(None)
      else
        Nil

    val classifiersArtifacts =
      if (classifiers.isEmpty)
        Nil
      else
        resolution.dependencyArtifacts(Some(classifiers.toSeq))

    val artifacts = (main ++ classifiersArtifacts).map {
      case (dep, attr, artifact) =>
        (dep.copy(attributes = dep.attributes.copy(classifier = attr.classifier)), attr, artifact)
    }

    if (artifactTypes0(Type.all))
      artifacts
    else
      artifacts.filter {
        case (_, attr, _) =>
          artifactTypes0(attr.`type`)
      }
  }

  private[coursier] def fetchArtifacts[F[_]](
    artifacts: Seq[Artifact],
    cache: Cache[F] = Cache.default
  )(implicit
     S: Sync[F]
  ): F[Seq[(Artifact, File)]] = {

    val tasks = artifacts.map { artifact =>
      val file0 = cache.file(artifact)
      S.map(file0.run)(artifact.->)
    }

    val gathered = S.gather(tasks)

    val loggerOpt = cache.loggerOpt

    val task = loggerOpt match {
      case None =>
        gathered
      case Some(logger) =>
        S.bind(S.delay(logger.init(sizeHint = Some(artifacts.length)))) { _ =>
          S.bind(S.attempt(gathered)) { a =>
            S.bind(S.delay(logger.stop())) { _ =>
              S.fromAttempt(a)
            }
          }
        }
    }

    S.bind(task) { results =>

      val ignoredErrors = new mutable.ListBuffer[(Artifact, ArtifactError)]
      val errors = new mutable.ListBuffer[(Artifact, ArtifactError)]
      val artifactToFile = new mutable.ListBuffer[(Artifact, File)]

      results.foreach {
        case (artifact, Left(err)) if artifact.optional && err.notFound =>
          ignoredErrors += artifact -> err
        case (artifact, Left(err)) =>
          errors += artifact -> err
        case (artifact, Right(f)) =>
          artifactToFile += artifact -> f
      }

      if (errors.isEmpty)
        S.point(artifactToFile.toList)
      else
        S.fromAttempt(Left(new FetchError.DownloadingArtifacts(errors.toList)))
    }
  }

}
