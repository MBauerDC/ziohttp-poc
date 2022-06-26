enablePlugins(GraalVMNativeImagePlugin)
enablePlugins(JavaAppPackaging)

scalaVersion := "3.1.2"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio"        % "2.0.0-RC6",
  "io.d11"  %% "zhttp"      % "2.0.0-RC9",
  "io.d11"  %% "zhttp-test" % "2.0.0-RC9",
  "io.d11" %% "zhttp-test" % "2.0.0-RC9" % Test,
  "dev.zio" %% "zio-json" % "0.3.0-RC8",
  //"org.typelevel" %% "shapeless3-deriving" % "3.1.0"
)

run / mainClass := Some("ag.dc.minimalzio.Main")

graalVMNativeImageOptions ++= Seq(
  "--static",
  "--no-fallback",
  "--install-exit-handlers",
  "--enable-http",
  "--initialize-at-run-time=io.netty.channel.DefaultFileRegion",
  "--initialize-at-run-time=io.netty.channel.epoll.Native",
  "--initialize-at-run-time=io.netty.channel.epoll.Epoll",
  "--initialize-at-run-time=io.netty.channel.epoll.EpollEventLoop",
  "--initialize-at-run-time=io.netty.channel.epoll.EpollEventArray",
  "--initialize-at-run-time=io.netty.channel.kqueue.KQueue",
  "--initialize-at-run-time=io.netty.channel.kqueue.KQueueEventLoop",
  "--initialize-at-run-time=io.netty.channel.kqueue.KQueueEventArray",
  "--initialize-at-run-time=io.netty.channel.kqueue.Native",
  "--initialize-at-run-time=io.netty.channel.unix.Limits",
  "--initialize-at-run-time=io.netty.channel.unix.Errors",
  "--initialize-at-run-time=io.netty.channel.unix.IovArray",
  "--initialize-at-run-time=io.netty.util.AbstractReferenceCounted",  
)

assembly / assemblyMergeStrategy := {
    case x if x.contains("META-INF") => (assemblyMergeStrategy in assembly).value.apply(x)
    case x if x.contains("io.netty")          => MergeStrategy.last
    case x => MergeStrategy.first
}

mainClass := Some("ag.dc.minimalzio.Main")

lazy val app = (project in file("minimalzio"))
  .settings(
    assembly / mainClass := Some("ag.dc.minimalzio.Main"),
  )
