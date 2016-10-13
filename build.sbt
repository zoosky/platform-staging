lazy val buildSettings = Seq(
  organization := "ch.epfl.scala",
  resolvers += Resolver.jcenterRepo,
  updateOptions := updateOptions.value.withCachedResolution(true)
)

lazy val compilerOptions = Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-unchecked",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Xfuture",
  "-Xlint"
)

lazy val commonSettings = Seq(
  triggeredMessage in ThisBuild := Watched.clearWhenTriggered,
  watchSources += baseDirectory.value / "resources",
  scalacOptions in (Compile, console) := compilerOptions,
  testOptions in Test += Tests.Argument("-oD"),
  scalaVersion := "2.11.8"
)

lazy val publishSettings = Seq(
  publishMavenStyle := true,
  publishArtifact := true,
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  publishArtifact in Test := false,
  licenses := Seq(
    // Scala Center license...
    "BSD 3-Clause License" -> url(
      "http://opensource.org/licenses/BSD-3-Clause")
  ),
  homepage := Some(url("https://github.com/scalaplatform/platform")),
  autoAPIMappings := true,
  apiURL := Some(url("https://scalaplatform.github.io/platform")),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/scalaplatform/platform"),
      "scm:git:git@github.com:scalaplatform/platform.git"
    )
  ),
  pomExtra :=
    <developers>
      <developer>
        <id>jvican</id>
        <name>Jorge Vicente Cantero</name>
        <url></url>
      </developer>
    </developers>
)

lazy val noPublish = Seq(
  publish := {},
  publishLocal := {}
)

lazy val allSettings = commonSettings ++ buildSettings ++ publishSettings

lazy val platform = project
  .in(file("."))
  .settings(allSettings)
  .settings(noPublish)
  .aggregate(process, `release-manager`)
  .dependsOn(process, `release-manager`)

lazy val process: Project = project
  .in(file("process"))
  .enablePlugins(OrnatePlugin)
  .settings(allSettings)
  .settings(
    name := "platform-process",
    ornateTargetDir := Some(file("docs/")),
    compile in Compile := Def.taskDyn {
      val analysis = (compile in Compile).value
      Def.task {
        (ornate in process).value
        // Work around Ornate limitation to add custom CSS
        val targetDir = (ornateTargetDir in process).value.get
        val cssFolder = targetDir / "_theme" / "css"
        val customCss = cssFolder / "custom.css"
        val mainCss = cssFolder / "app.css"
        IO.append(mainCss, IO.read(customCss))
        analysis
      }
    }.value
  )

lazy val `release-manager` = project
  .in(file("release-manager"))
  .settings(allSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.eclipse.jgit" % "org.eclipse.jgit" % "4.5.0.201609210915-r",
      "com.github.jvican" %% "stoml" % "0.1",
      "org.scalatest" %% "scalatest" % "3.0.0" % "test",
      "junit" % "junit" % "4.12" % "test"
    )
  )

val circeVersion = "0.5.1"
lazy val `sbt-utils` = project
  .in(file("utils"))
  .settings(allSettings)
  .settings(ScriptedPlugin.scriptedSettings)
  .settings(
    sbtPlugin := true,
    scalaVersion := "2.10.5",
    libraryDependencies ++= Seq(
      "com.eed3si9n" %% "gigahorse-core" % "0.1.1",
      "com.lihaoyi" %% "sourcecode" % "0.1.2",
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion
    ),
    scriptedLaunchOpts := Seq(
      "-Dplugin.version=" + version.value,
      // .jvmopts is ignored, simulate here
      "-XX:MaxPermSize=256m",
      "-Xmx2g",
      "-Xss2m"
    ),
    scriptedBufferLog := false,
    addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.3"),
    addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "1.1"),
    addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.0.0"),
    addCompilerPlugin(
      "org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full
    )
  )
