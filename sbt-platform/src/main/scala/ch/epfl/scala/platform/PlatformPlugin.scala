package ch.epfl.scala.platform

import cats.data.Xor
import ch.epfl.scala.platform
import ch.epfl.scala.platform.github.GitHubReleaser
import ch.epfl.scala.platform.github.GitHubReleaser.{GitHubEndpoint, GitHubRelease}
import ch.epfl.scala.platform.search.{ModuleSearch, ScalaModule}
import ch.epfl.scala.platform.util.Error
import coursier.core.Version
import coursier.core.Version.{Literal, Qualifier}
import org.joda.time.DateTime
import sbt._
import sbt.complete.Parser
import sbtrelease.ReleasePlugin.autoImport.ReleaseStep
import sbtrelease.{Git, ReleaseStateTransformations}
import sbtrelease.Version.Bump

import scala.util.{Random, Try}

object PlatformPlugin extends sbt.AutoPlugin {

  object autoImport extends PlatformSettings

  override def trigger = allRequirements

  override def requires =
    bintray.BintrayPlugin &&
      sbtrelease.ReleasePlugin &&
      com.typesafe.sbt.SbtPgp &&
      com.typesafe.tools.mima.plugin.MimaPlugin

  override def projectSettings = PlatformKeys.settings
}

trait PlatformSettings {
  def getEnvVariable(key: String): Option[String] = sys.env.get(key)

  def getDroneEnvVariableOrDie(key: String) = {
    getEnvVariable(key)
      .getOrElse(sys.error(Feedback.undefinedEnvironmentVariable(key)))
  }

  def getDroneEnvVariableOrDie[T](key: String, conversion: String => T): T = {
    getEnvVariable(key)
      .map(conversion)
      .getOrElse(sys.error(Feedback.undefinedEnvironmentVariable(key)))
  }

  def toBoolean(presumedBoolean: String) = presumedBoolean.toBoolean

  def toInt(presumedInt: String) = presumedInt.toInt

  case class CIEnvironment(rootDir: File,
                           name: String,
                           repo: String,
                           branch: String,
                           commit: String,
                           buildDir: String,
                           buildUrl: String,
                           buildNumber: Int,
                           pullRequest: Option[String],
                           jobNumber: Int,
                           tag: Option[String])

  // CI-defined environment variables
  val insideCi = settingKey[Boolean]("Checks if CI is executing the build.")
  val ciEnvironment = settingKey[Option[CIEnvironment]]("Get the Drone environment.")

  // Custom environment variables
  val sonatypeUsername = settingKey[Option[String]]("Get sonatype username.")
  val sonatypePassword = settingKey[Option[String]]("Get sonatype password.")

  // FORMAT: ON
  val platformLogger = taskKey[Logger]("Return the sbt logger.")
  val platformReleaseOnMerge = settingKey[Boolean]("Release on every PR merge.")
  val platformModuleTags = settingKey[Seq[String]]("Tags for the bintray module package.")
  val platformTargetBranch = settingKey[String]("Branch used for the platform release.")
  val platformValidatePomData = taskKey[Unit]("Ensure that all the data is available before generating a POM file.")
  // TODO(jvican): Make sure every sbt subproject has an independent module
  val platformScalaModule = settingKey[ScalaModule]("Create the ScalaModule from the basic assert info.")
  val platformModuleBaseVersion = settingKey[Version]("Get the sbt-defined version of the current module.")
  val platformFetchLatestPublishedVersion = taskKey[Version]("Fetch latest published stable version.")
  val platformRunMiMa = taskKey[Unit]("Run MiMa and report results based on current version.")
  val platformGitHubToken = settingKey[String]("Token to publish releses to GitHub.")
  val platformReleaseNotesDir = settingKey[File]("Directory with the markdown release notes.")
  val platformGetReleaseNotes = taskKey[String]("Get the correct release notes for a release.")
  val platformReleaseToGitHub = taskKey[Unit]("Create a release in GitHub.")
  val platformGitHubRepo = settingKey[Option[(String, String)]]("Get GitHub organization and repository from .git folder.")
  val platformNightlyReleaseProcess = settingKey[Seq[ReleaseStep]]("The nightly release process for a Platform module.")
  val platformSignArtifact = settingKey[Boolean]("Enable to sign artifacts with the platform pgp key.")
  val platformCustomRings = settingKey[Option[File]]("File that stores the pgp secret ring.")
  val platformDefaultPublicRingName = settingKey[String]("Default file name for fetching the public gpg keys.")
  val platformDefaultPrivateRingName = settingKey[String]("Default file name for fetching the private gpg keys.")
  // Release process hooks -- useful for easily extending the default release process
  val platformBeforePublishHook = taskKey[Unit]("A release hook to customize the beginning of the release process.")
  val platformAfterPublishHook = taskKey[Unit]("A release hook to customize the end of the release process.")
  // FORMAT: OFF
}

object PlatformKeys {

  import PlatformPlugin.autoImport._

  def settings: Seq[Setting[_]] =
    resolverSettings ++ compilationSettings ++ publishSettings ++ platformSettings

  import sbt._, Keys._
  import sbtrelease.ReleasePlugin.autoImport._
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

  import com.typesafe.sbt.SbtPgp.autoImport._

  lazy val defaultReleaseSettings = Seq(
    releasePublishArtifactsAction := PgpKeys.publishSigned.value,
    // Empty the default release process to avoid errors
    releaseProcess := Seq.empty[ReleaseStep],
    platformNightlyReleaseProcess := PlatformReleaseProcess.Nightly.releaseProcess
  )

  val emptyModules = Set.empty[ModuleID]
  lazy val platformSettings: Seq[Setting[_]] = Seq(
    insideCi := getEnvVariable("CI").exists(toBoolean),
    ciEnvironment := {
      if (!insideCi.value) None
      else {
        for {
          ciName <- getEnvVariable("CI_NAME")
          ciRepo <- getEnvVariable("CI_REPO")
          ciBranch <- getEnvVariable("CI_BRANCH")
          ciCommit <- getEnvVariable("CI_COMMIT")
          ciBuildDir <- getEnvVariable("CI_BUILD_DIR")
          ciBuildUrl <- getEnvVariable("CI_BUILD_URL")
          ciBuildNumber <- getEnvVariable("CI_BUILD_NUMBER")
          ciJobNumber <- getEnvVariable("CI_JOB_NUMBER")
        } yield CIEnvironment(file("/drone"),
          ciName,
          ciRepo,
          ciBranch,
          ciCommit,
          ciBuildDir,
          ciBuildUrl,
          ciBuildNumber.toInt,
          getEnvVariable("CI_PULL_REQUEST"),
          ciJobNumber.toInt,
          getEnvVariable("CI_TAG"))
      }
    },
    sonatypeUsername := getEnvVariable("SONATYPE_USERNAME"),
    sonatypePassword := getEnvVariable("SONATYPE_PASSWORD"),
    platformLogger := streams.value.log,
    platformReleaseOnMerge := false, // By default, disabled
    platformModuleTags := Seq.empty[String],
    platformTargetBranch := "platform-release",
    platformValidatePomData := {
      if (scmInfo.value.isEmpty)
        throw new NoSuchElementException(Feedback.forceDefinitionOfScmInfo)
      if (licenses.value.isEmpty)
        throw new NoSuchElementException(Feedback.forceValidLicense)
      bintrayEnsureLicenses.value
    },
    platformModuleBaseVersion := {
      if (version.value.isEmpty) sys.error(Feedback.unexpectedEmptyVersion)
      val definedVersion = version.value
      val validatedVersion = for {
        version <- Try(Version(definedVersion)).toOption
        if !version.items.exists(_.isInstanceOf[Literal])
      } yield version
      validatedVersion.getOrElse(
        sys.error(Feedback.malformattedVersion(definedVersion)))
    },
    platformScalaModule := {
      val org = organization.value
      val artifact = moduleName.value
      val version = scalaBinaryVersion.value
      ScalaModule(org, artifact, version)
    },
    mimaPreviousArtifacts := {
      val highPriorityArtifacts = mimaPreviousArtifacts.value
      if (highPriorityArtifacts.isEmpty) {
        /* This is a setting because modifies previousArtifacts, so we protect
         * ourselves from errors if users don't have connection to Internet. */
        val targetModule = platformScalaModule.value
        Helper.getPublishedArtifacts(targetModule)
      } else highPriorityArtifacts
    },
    platformFetchLatestPublishedVersion := {
      val previousArtifacts = mimaPreviousArtifacts.value
      // Retry in case where sbt boots up without Internet connection
      val retryPreviousArtifacts =
      if (previousArtifacts.nonEmpty) previousArtifacts
      else Helper.getPublishedArtifacts(platformScalaModule.value)
      val moduleId = retryPreviousArtifacts.headOption.getOrElse(
        sys.error(Feedback.forceDefinitionOfPreviousArtifacts))
      Version(moduleId.revision)
    },
    platformRunMiMa := {
      val currentVersion = platformModuleBaseVersion.value
      val previousVersion = platformFetchLatestPublishedVersion.value
      mimaFailOnProblem := currentVersion.items.head > previousVersion.items.head
      if (version.value.startsWith("0.") && mimaPreviousArtifacts.value.isEmpty)
        sys.error(Feedback.forceDefinitionOfPreviousArtifacts)
      mimaReportBinaryIssues.value
    },
    platformReleaseNotesDir := baseDirectory.value / "notes",
    platformGetReleaseNotes := {
      val mdFile = s"${version.value}.md"
      val markdownFile = s"${version.value}.markdown"
      val notes = List(mdFile, markdownFile).foldLeft("") { (acc, curr) =>
        if (acc.nonEmpty) acc
        else {
          val presumedFile = platformReleaseNotesDir.value / curr
          if (!presumedFile.exists) acc
          else IO.read(presumedFile)
        }
      }
      if (notes.isEmpty)
        platformLogger.value.info("Release notes are empty.")
      notes
    },
    platformGitHubRepo := {
      releaseVcs.value match {
        case Some(g: Git) =>
          // Fetch git endpoint automatically
          if (g.trackingRemote.isEmpty) sys.error(Feedback.incorrectGitHubRepo)
          val trackingRemote = g.trackingRemote
          val p = g.cmd("config", "remote.%s.url" format trackingRemote)
          val gitResult = p.!!.trim
          gitResult match {
            case GitHubReleaser.SshGitHubUrl(org, repo) => Some(org, repo)
            case GitHubReleaser.HttpsGitHubUrl(org, repo) => Some(org, repo)
            case _ => sys.error(Feedback.incorrectGitHubUrl(trackingRemote, gitResult))
          }
        case Some(vcs) => sys.error("Only git is supported for now.")
        case None => None
      }
    },
    scmInfo := {
      scmInfo.value.orElse {
        platformGitHubRepo.value.map { t =>
          val (org, repo) = t
          val gitHubUrl = GitHubReleaser.generateGitHubUrl(org, repo)
          ScmInfo(url(gitHubUrl), s"scm:git:$gitHubUrl")
        }
      }
    },
    platformReleaseToGitHub := {
      def createReleaseInGitHub(org: String, repo: String, token: String) = {
        val endpoint = GitHubEndpoint(org, repo, token)
        val notes = platformGetReleaseNotes.value
        val releaseVersion = Version(version.value)
        platformLogger.value.info(s"Releasing $releaseVersion to GitHub($org, $repo, $token)")
        val release = GitHubRelease(releaseVersion, notes)
        endpoint.pushRelease(release)
      }

      // TODO(jvican): Change environment name in Drone
      val tokenEnvName = "GITHUB_PLATFORM_TEST_TOKEN"
      val githubToken = sys.env.get(tokenEnvName)
      githubToken match {
        case Some(token) =>
          val (org, repo) = platformGitHubRepo.value.getOrElse(
            sys.error(Feedback.incorrectGitHubRepo))
          createReleaseInGitHub(org, repo, token)
        case None =>
          sys.error(Feedback.undefinedEnvironmentVariable(tokenEnvName))
      }
    },
    platformSignArtifact := true,
    platformCustomRings := None,
    platformDefaultPublicRingName := "platform.pubring.asc",
    platformDefaultPrivateRingName := "platform.secring.asc",
    pgpSigningKey := {
      val PlatformPgpKey = "11BCFDCC60929524"
      if (platformSignArtifact.value) {
        Some(new java.math.BigInteger(PlatformPgpKey, 16).longValue)
      } else None
    },
    pgpPassphrase := {
      if (platformSignArtifact.value)
        sys.env.get("PLATFORM_PGP_PASSPHRASE").map(_.toCharArray)
      else None
    },
    pgpPublicRing := {
      if (platformSignArtifact.value) {
        Helper.getPgpRingFile(
          ciEnvironment.value,
          platformCustomRings.value,
          platformDefaultPublicRingName.value)
      } else pgpPublicRing.value
    },
    pgpSecretRing := {
      if (platformSignArtifact.value) {
        Helper.getPgpRingFile(
          ciEnvironment.value,
          platformCustomRings.value,
          platformDefaultPrivateRingName.value)
      } else pgpSecretRing.value
    },
    platformBeforePublishHook := {},
    platformAfterPublishHook := {},
    commands += PlatformReleaseProcess.Nightly.releaseCommand
  )

  object Helper {
    def getPublishedArtifacts(targetModule: ScalaModule): Set[ModuleID] = {
      val response = ModuleSearch.searchLatest(targetModule)
      val moduleResponse = response.map(_.map(rmod =>
        targetModule.orgId %% targetModule.artifactId % rmod.latest_version))
      moduleResponse.map(_.map(Set[ModuleID](_)).getOrElse(emptyModules))
        .getOrElse(emptyModules)
    }

    def getPgpRingFile(ciEnvironment: Option[CIEnvironment],
                       customRing: Option[File],
                       defaultRingFileName: String) = {
      ciEnvironment.map {
        _.rootDir / ".gnupg" / defaultRingFileName
      }.getOrElse {
        if (customRing.isEmpty) {
          val homeFolder = System.getProperty("user.home")
          if (homeFolder.isEmpty) sys.error("Define $HOME to start with.")
          else file(s"$homeFolder/.gnupg/$defaultRingFileName")
        } else customRing.get
      }
    }
  }

  object PlatformReleaseProcess {

    import ReleaseKeys._
    import sbtrelease.Utilities._
    import sbtrelease.ReleaseStateTransformations._

    val commandLineDefinedVersion = AttributeKey[Option[String]]("commandLineDefinedVersion")
    val validReleaseVersion = AttributeKey[Version]("validatedReleaseVersions")

    private def validateVersion(definedVersion: String): Version = {
      val validatedVersion = for {
        version <- Try(Version(definedVersion)).toOption
        if !version.items.exists(
          i => i.isInstanceOf[Literal] || i.isInstanceOf[Qualifier])
      } yield version
      validatedVersion.getOrElse(
        sys.error(Feedback.malformattedVersion(definedVersion)))
    }

    private def generateUbiquituousVersion(version: String, st: State) = {
      val ci = st.extract.get(ciEnvironment)
      val unique = ci.map(_.buildNumber.toString)
        .getOrElse(Random.nextLong.abs.toString)
      s"$version-$unique"
    }

    val validateAndSetVersion: ReleaseStep = { (st0: State) =>
      val (st, logger) = st0.extract.runTask(platformLogger, st0)
      val userDefinedVersion = st.get(commandLineDefinedVersion).flatten.map(
        validateVersion)
      val definedVersion = userDefinedVersion
        .getOrElse(st.extract.get(platformModuleBaseVersion))
      val sbtReleaseVersion = sbtrelease.Version(definedVersion.repr).get
      // TODO(jvican): Make sure minor and major depend on platform version
      val nextVersion = Bump.Minor.bump.apply(sbtReleaseVersion).string
      logger.info(s"Current version is $definedVersion.")
      logger.info(s"Next version is set to $nextVersion.")
      st.put(validReleaseVersion, definedVersion)
        .put(versions, (definedVersion.repr, nextVersion))
    }

    val checkVersionIsNotPublished: ReleaseStep = { (st: State) =>
      val definedVersion = st.get(validReleaseVersion).getOrElse(
        sys.error(Feedback.undefinedVersion))
      val module = st.extract.get(platformScalaModule)
      ModuleSearch.exists(module, definedVersion).flatMap { exists =>
        if (!exists) Xor.right(st)
        else Xor.left(Error(s"Version ${definedVersion.repr} is already published."))
        // TODO(jvican): Improve error handling here
      }.fold(e => sys.error(e.msg), identity)
    }

    object Nightly {
      val tagAsNightly: ReleaseStep = { (st0: State) =>
        val (st, logger) = st0.extract.runTask(platformLogger, st0)
        val targetVersion = st.get(validReleaseVersion).getOrElse(sys.error(
          "Unexpected `tagAsNightly`, have you run `validateAndSetVersion` before?"))
        val now = DateTime.now()
        val month = now.dayOfMonth().get
        val day = now.monthOfYear().get
        val year = now.year().get
        val template = s"${targetVersion.repr}-alpha-$year-$month-$day"
        val nightlyVersion =
          if (!platform.testing) template
          else generateUbiquituousVersion(template, st)
        val generatedVersion = targetVersion.copy(nightlyVersion)
        logger.info(s"Nightly version is set to ${generatedVersion.repr}.")
        val previousVersions = st.get(versions).getOrElse(
          sys.error("Undefined versions, did you properly define the versions?"))
        val updatedState = st.put(validReleaseVersion, generatedVersion)
          .put(versions, (nightlyVersion, previousVersions._2))
        // Reuse versions and current sbt-related related tasks
        setReleaseVersion(updatedState)
      }

      import ReleaseKeys._

      val releaseParser: Parser[Seq[ParseResult]] =
        (ReleaseVersion | SkipTests | CrossBuild).*
      val FailureCommand = "--failure--"

      val releaseCommand: Command = Command("releaseNightly")(
        _ => releaseParser) { (st, args) =>
        val extracted = Project.extract(st)
        val releaseParts = extracted.get(platformNightlyReleaseProcess)
        val crossEnabled = extracted.get(releaseCrossBuild) ||
          args.contains(ParseResult.CrossBuild)

        val startState = st
          .copy(onFailure = Some(FailureCommand))
          .put(skipTests, args.contains(ParseResult.SkipTests))
          .put(cross, crossEnabled)
          .put(commandLineDefinedVersion,
            args.collectFirst { case ParseResult.ReleaseVersion(value) => value })

        val initialChecks = releaseParts.map(_.check)
        val process = releaseParts.map { step =>
          if (step.enableCrossBuild && crossEnabled) {
            filterFailure(ReleaseStateTransformations.runCrossBuild(step.action)) _
          } else filterFailure(step.action) _
        }

        val removeFailureCommand = { s: State =>
          s.remainingCommands match {
            case FailureCommand :: tail => s.copy(remainingCommands = tail)
            case _ => s
          }
        }
        initialChecks.foreach(_ (startState))
        Function.chain(process :+ removeFailureCommand)(startState)
      }

      val releaseProcess = {
        Seq[ReleaseStep](
          validateAndSetVersion,
          tagAsNightly,
          checkVersionIsNotPublished,
          releaseStepTask(platformValidatePomData),
          checkSnapshotDependencies,
          runTest,
          releaseStepTask(platformRunMiMa),
          releaseStepTask(platformBeforePublishHook),
          publishArtifacts,
          releaseStepTask(platformAfterPublishHook),
          releaseStepTask(bintrayRelease)
        )
      }
    }

  }

}
