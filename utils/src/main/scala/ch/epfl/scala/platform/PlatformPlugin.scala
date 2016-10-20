package ch.epfl.scala.platform

import sbt._

import ch.epfl.scala.platform.search.{ModuleSearch, ScalaModule}

object PlatformPlugin extends sbt.AutoPlugin {
  object autoImport extends PlatformSettings
  override def trigger = allRequirements
  override def requires =
    bintray.BintrayPlugin &&
      sbtrelease.ReleasePlugin &&
      com.typesafe.sbt.SbtPgp &&
      com.typesafe.tools.mima.plugin.MimaPlugin

  override def projectSettings = PlatformSettings.settings
}

trait PlatformSettings {
  def getEnvVariable(key: String): Option[String] = sys.env.get(key)
  def toBoolean(presumedBoolean: String) = presumedBoolean.toBoolean
  def toInt(presumedInt: String) = presumedInt.toInt

  // Drone-defined environment variables
  val insideCi = settingKey[Boolean]("Checks if CI is executing the build.")
  val ciName = settingKey[Option[String]]("Get the name of the CI server.")
  val ciRepo = settingKey[Option[String]]("Get the repository run by the CI.")
  val ciBranch = settingKey[Option[String]]("Get the current git branch.")
  val ciCommit = settingKey[Option[String]]("Get the current git commit.")
  val ciBuildDir = settingKey[Option[String]]("Get the CI build directory.")
  val ciBuildUrl = settingKey[Option[String]]("Get the CI build URL.")
  val ciBuildNumber = settingKey[Option[Int]]("Get the CI build number.")
  val ciPullRequest = settingKey[Option[String]]("Get the pull request id.")
  val ciJobNumber = settingKey[Option[Int]]("Get the CI job number.")
  val ciTag = settingKey[Option[String]]("Get the git tag.")

  // Custom environment variables
  val sonatypeUsername = settingKey[Option[String]]("Get sonatype username.")
  val sonatypePassword = settingKey[Option[String]]("Get sonatype password.")
  val bintrayUsername = settingKey[Option[String]]("Get bintray username.")
  val bintrayPassword = settingKey[Option[String]]("Get bintray password.")

  // FORMAT: OFF
  val platformReleaseOnMerge = settingKey[Boolean]("Release on every PR merge.")
  val platformModuleTags = settingKey[Seq[String]]("Tags for the bintray module package.")
  val platformTargetBranch = settingKey[String]("Branch used for the platform release.")
  val platformValidatePomData = taskKey[Unit]("Ensure that all the data is available before generating a POM file.")
  val platformFetchPreviousArtifact = settingKey[Set[ModuleID]]("Fetch latest previous published artifact for MiMa checks.")

  // Release process hooks -- useful for easily extending the default release process
  val platformBeforePublishHook = taskKey[Unit]("A release hook to customize the beginning of the release process.")
  val platformAfterPublishHook = taskKey[Unit]("A release hook to customize the end of the release process.")
  // FORMAT: ON
}

object PlatformSettings {

  import PlatformPlugin.autoImport._

  def settings: Seq[Setting[_]] =
    resolverSettings ++ compilationSettings ++ publishSettings ++ platformSettings

  import sbt._, Keys._
  import sbtrelease.ReleasePlugin.autoImport._
  import ReleaseTransformations._
  import bintray.BintrayPlugin.autoImport._
  import com.typesafe.tools.mima.plugin.MimaPlugin.autoImport._

  private val PlatformReleases =
    Resolver.bintrayRepo("scalaplatform", "modules-releases")
  private val PlatformTools =
    Resolver.bintrayRepo("scalaplatform", "tools")

  lazy val resolverSettings = Seq(
    resolvers ++= Seq(PlatformReleases, PlatformTools))

  private val defaultCompilationFlags =
    Seq("-deprecation", "-encoding", "UTF-8", "-unchecked")
  private val twoLastScalaVersions = Seq("2.10.6", "2.11.8")
  lazy val compilationSettings: Seq[Setting[_]] = Seq(
    scalacOptions in Compile ++= defaultCompilationFlags,
    crossScalaVersions in Compile := twoLastScalaVersions
  )

  lazy val publishSettings: Seq[Setting[_]] = Seq(
      bintrayOrganization := Some("scalaplatform"),
      publishTo := (publishTo in bintray).value,
      // Necessary for synchronization with Maven Central
      publishMavenStyle := true,
      bintrayReleaseOnPublish in ThisBuild := false,
      releaseCrossBuild := true
    ) ++ defaultReleaseSettings

  /** Define custom release steps and add them to the default pipeline. */
  import com.typesafe.sbt.pgp.PgpKeys
  lazy val defaultReleaseSettings = Seq(
    releasePublishArtifactsAction := PgpKeys.publishSigned.value,
    // Use the nightly release process by default...
    releaseProcess := SbtReleaseSettings.Nightly.releaseProcess
  )

  lazy val platformSettings: Seq[Setting[_]] = Seq(
    insideCi := getEnvVariable("CI").exists(toBoolean),
    ciName := getEnvVariable("CI_NAME"),
    ciRepo := getEnvVariable("CI_REPO"),
    ciBranch := getEnvVariable("CI_BRANCH"),
    ciCommit := getEnvVariable("CI_COMMIT"),
    ciBuildDir := getEnvVariable("CI_BUILD_DIR"),
    ciBuildUrl := getEnvVariable("CI_BUILD_URL"),
    ciBuildNumber := getEnvVariable("CI_BUILD_NUMBER").map(toInt),
    ciPullRequest := getEnvVariable("CI_PULL_REQUEST"),
    ciJobNumber := getEnvVariable("CI_JOB_NUMBER").map(toInt),
    ciTag := getEnvVariable("CI_TAG"),
    sonatypeUsername := getEnvVariable("SONATYPE_USERNAME"),
    sonatypePassword := getEnvVariable("SONATYPE_PASSWORD"),
    bintrayUsername := getEnvVariable("BINTRAY_USERNAME"),
    bintrayPassword := getEnvVariable("BINTRAY_PASSWORD"),
    platformReleaseOnMerge := false, // By default, disabled
    platformModuleTags := Seq.empty[String],
    platformTargetBranch := "platform-release",
    platformValidatePomData := {
      if (bintrayVcsUrl.value.isEmpty)
        throw new NoSuchElementException(Feedback.forceDefinitionOfScmInfo)
      if (licenses.value.isEmpty)
        throw new NoSuchElementException(Feedback.forceValidLicense)
      bintrayEnsureLicenses.value
    },
    platformFetchPreviousArtifact := {
      val org = organization.value
      val artifact = moduleName.value
      val version = scalaBinaryVersion.value
      val targetModule = ScalaModule(org, artifact, version)
      val response = ModuleSearch.searchLatest(targetModule)
      val previousArtifacts =
        response.map(Set[ModuleID](_)).getOrElse(Set.empty[ModuleID])
      if (previousArtifacts.isEmpty)
        sys.error(Feedback.forceDefinitionOfPreviousArtifacts)
      previousArtifacts
    },
    mimaPreviousArtifacts := platformFetchPreviousArtifact.value
  )

  object SbtReleaseSettings {
    object Nightly {
      val releaseProcess = {
        Seq[ReleaseStep](
          checkSnapshotDependencies,
          releaseStepTask(platformValidatePomData),
          runTest,
          releaseStepTask(mimaReportBinaryIssues),
          inquireVersions,
          setReleaseVersion,
          commitReleaseVersion,
          tagRelease,
          releaseStepTask(platformBeforePublishHook),
          publishArtifacts,
          releaseStepTask(platformAfterPublishHook),
          setNextVersion,
          commitNextVersion,
          pushChanges
        )
      }
    }
  }
}
