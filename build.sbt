import Dependencies.*
import RescalaDependencies.*
import Settings.*


// single module SBT projects don’t have to be defined as its own val,
// but it is good style to do it anyway
lazy val todolist = project.in(file("."))
  // plugin to produce JS output instead of JVM
  .enablePlugins(ScalaJSPlugin)
  .settings(
    // select the scala version, and include some default compiler options
    // see `project/Settings.scala` for more details on how this is defined
    scalaVersion_3,
    // https://jitpack.io/ is a service that builds jar files from source code
    // this allows us to include the snapshot versions below
    resolverJitpack,
    // below are the dependencies we use, see `project/dependencies.scala` for the definitions
    libraryDependencies ++= Seq(
      // library to generate HTML
      scalatags.value,
      // https://scala-loci.github.io/
      // a project for “multitier programming” we use it for its facility to connect over various transports
      loci.webrtc.value,
      loci.jsoniterScala.value,
      // jsoniter is a JSON serialization library
      jsoniterScala.value,
      // reactives (rescala) and replicated data types (kofre)
      "com.github.rescala-lang.rescala" %%% "rescala" % "136710028d",
      "com.github.rescala-lang.rescala" %%% "kofre"   % "136710028d",
    ),
    // scalajs is very conservative, this falls back to a “potentially unsafe default implementation” of the task scheduler that is fine for us
    jsAcceptUnfairGlobalTasks,
    // this is a custom sbt task, write `deploy` in the sbt console to execute
    // the below is just more or less normal scala using SBT as a library
    // what it does is to take the `index.template.html` and fill the output path for the generated js file
    TaskKey[File]("deploy", "generates a correct index.html for the todolist app") := {
      val fastlink   = (Compile / fastLinkJS).value
      val jspath     = (Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value
      val bp         = baseDirectory.value.toPath
      val tp         = target.value.toPath
      val template   = IO.read(bp.resolve("index.template.html").toFile)
      val targetpath = tp.resolve("index.html")
      val jsrel      = targetpath.getParent.relativize(jspath.toPath)
      IO.write(targetpath.toFile, template.replace("JSPATH", s"${jsrel}/main.js"))
      IO.copyFile(bp.resolve("todolist.css").toFile, tp.resolve("todolist.css").toFile)
      targetpath.toFile
    }
  )
