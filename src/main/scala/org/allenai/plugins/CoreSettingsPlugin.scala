package org.allenai.plugins

import com.typesafe.sbt.SbtScalariform
import sbt.Keys._
import sbt._

import scalariform.formatter.ScalaFormatter
import scalariform.formatter.preferences._
import scalariform.parser.ScalaParserException

object CoreSettingsPlugin extends AutoPlugin {

  // Automatically add the StylePlugin and VersionInjectorPlugin
  override def requires: Plugins = SbtScalariform && StylePlugin && VersionInjectorPlugin

  // Automatically enable the plugin (no need for projects to `enablePlugins(CoreSettingsPlugin)`)
  override def trigger: PluginTrigger = allRequirements

  object autoImport {
    val CoreResolvers = CoreRepositories.Resolvers
    val PublishTo = CoreRepositories.PublishTo

    val generateRunClass = taskKey[File](
      "creates the run-class.sh script in the managed resources directory"
    )

    val scalariformPreferences = settingKey[IFormattingPreferences](
      "The Scalariform preferences to use in formatting."
    )

    val format = taskKey[Seq[File]]("Format all scala source files, returning the changed files")

    val formatCheck = taskKey[Seq[File]](
      "Check for misformatted scala files, and print out & return those with errors"
    )

    val formatCheckStrict = taskKey[Unit](
      "Check for misformatted scala files, print out the names of those with errors, " +
        "and throw an error if any do have errors"
    )
  }

  import autoImport._

  private val generateRunClassTask = autoImport.generateRunClass := {
    val logger = streams.value.log
    logger.debug("Generating run-class.sh")
    val file = (resourceManaged in Compile).value / "run-class.sh"
    // Read the plugin's resource file.
    val contents = {
      val is = this.getClass.getClassLoader.getResourceAsStream("run-class.sh")
      try {
        IO.readBytes(is)
      } finally {
        is.close()
      }
    }

    // Copy the contents to the clients managed resources.
    IO.write(file, contents)
    logger.debug(s"Wrote ${contents.size} bytes to ${file.getPath}.")

    file
  }

  case class FormatResult(sourceFile: File, original: String, formatted: String)

  // Private task implementation for generating output.
  // Returns FormatResult for all *.scala files in `sourceDirectories`, also honoring the in-scope
  // `includeFilter` and `excludeFilter`.
  private val formatInternal = Def.task {
    val preferences = scalariformPreferences.value
    // Find all of the scala source files, then run them through scalariform.
    val sourceFiles = sourceDirectories.value.descendantsExcept(
      includeFilter.value || "*.scala",
      excludeFilter.value
    ).get
    val scalaMajorVersion = scalaVersion.value.split("-").head
    for {
      sourceFile <- sourceFiles
      original = IO.read(sourceFile)
      formatted = try {
        ScalaFormatter.format(original, preferences, scalaVersion = scalaMajorVersion)
      } catch {
        // A sclariform parse error generally means a file that won't compile.
        case e: ScalaParserException =>
          streams.value.log.error(s"Scalariform parser error in file $sourceFile: ${e.getMessage}")
          original
      }
    } yield FormatResult(sourceFile, original, formatted)
  }

  val baseScalariformSettings: Seq[Def.Setting[_]] = Seq(
    format := {
      // The mainline SbtScalariform uses FileFunction to cache this, but it's not really worth the
      // effort here - especially given that we actually don't want to cache for formatCheck.
      for {
        FormatResult(sourceFile, original, formatted) <- formatInternal.value
        if original != formatted
      } yield {
        // Shorten the name to a friendlier path.
        val shortName = sourceFile.relativeTo(baseDirectory.value).getOrElse(sourceFile)
        streams.value.log.info(s"Formatting $shortName . . .")
        IO.write(sourceFile, formatted)
        sourceFile
      }
    },
    formatCheck := {
      val misformatted = for {
        FormatResult(sourceFile, original, formatted) <- formatInternal.value
        if original != formatted
      } yield sourceFile

      if (misformatted.nonEmpty) {
        val log = streams.value.log
        log.error("""Some files contain formatting errors; please run "sbt format" to fix.""")
        log.error("")
        log.error("Files with errors:")
        for (result <- misformatted) {
          // TODO(jkinkead): Log some / all of the diffs?
          log.error(s"\t$result")
        }
      }
      misformatted
    },
    formatCheckStrict := {
      val misformatted = formatCheck.value
      if (misformatted.nonEmpty) {
        throw new MessageOnlyException("Some files have formatting errors.")
      }
    }
  )

  // Add the IntegrationTest config to the project. The `extend(Test)` part makes it so
  // classes in src/it have a classpath dependency on classes in src/test. This makes
  // it simple to share common test helper code.
  // See http://www.scala-sbt.org/release/docs/Testing.html#Custom+test+configuration
  override val projectConfigurations = Seq(Configurations.IntegrationTest extend (Test))

  // These settings will be automatically applied to projects
  override def projectSettings: Seq[Setting[_]] = {
    Defaults.itSettings ++
      SbtScalariform.defaultScalariformSettingsWithIt ++
      inConfig(Compile)(baseScalariformSettings) ++
      inConfig(Test)(baseScalariformSettings) ++
      inConfig(Configurations.IntegrationTest)(baseScalariformSettings) ++
      Seq(
        generateRunClassTask,
        fork := true, // Forking for run, test is required sometimes, so fork always.
        scalaVersion := CoreDependencies.defaultScalaVersion,
        scalacOptions ++= Seq("-target:jvm-1.7", "-Xlint", "-deprecation", "-feature"),
        javacOptions ++= Seq("-source", "1.8", "-target", "1.8"),
        resolvers ++= CoreRepositories.Resolvers.defaults,
        dependencyOverrides ++= CoreDependencies.loggingDependencyOverrides,
        dependencyOverrides += "org.scala-lang" % "scala-library" % scalaVersion.value,
        // Override default scalariform settings.
        SbtScalariform.autoImport.scalariformPreferences := {
          FormattingPreferences()
            .setPreference(DoubleIndentClassDeclaration, true)
            .setPreference(MultilineScaladocCommentsStartOnFirstLine, true)
            .setPreference(PlaceScaladocAsterisksBeneathSecondAsterisk, true)
            .setPreference(SpacesAroundMultiImports, true)
        },
        // Configure root-level tasks to aggregate accross configs
        format := {
          (format in Compile).value
          (format in Test).value
          (format in IntegrationTest).value
        },
        formatCheck := {
          (formatCheck in Compile).value
          (formatCheck in Test).value
          (formatCheck in IntegrationTest).value
        },
        formatCheckStrict := {
          (formatCheckStrict in Compile).value
          (formatCheckStrict in Test).value
          (formatCheckStrict in IntegrationTest).value
        }
      )
  }
}
