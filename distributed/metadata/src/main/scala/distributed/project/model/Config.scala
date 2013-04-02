package distributed.project.model
import com.fasterxml.jackson.annotation.JsonProperty

// import distributed.support.sbt.SbtConfig


/**
 * Metadata about a build.  This is extracted from a config file and contains enough information
 * to further extract information about a build.
 */
case class ProjectBuildConfig(name: String, 
    system: String = "sbt",
    uri: String, 
    extra: Option[ExtraConfig]=None) {
  def uuid = hashing sha1 this
}

/** The initial configuration for a build. */
case class DistributedBuildConfig(projects: Seq[ProjectBuildConfig])


/** Configuration used for SBT and other builds.
 *  We need to have a single structure for all build systems;
 *  not all fields will be meaningful or used for all the
 *  build system. A reasonable default should be provided
 *  for all fields. */
// TODO - Autogenerate SBT versions!
case class ExtraConfig(
    @JsonProperty("build-tool-version")
      buildToolVersion: String = "", // Note: empty version is interpreted as default, when the Build System extracts this bit
    directory: String = "",
    @JsonProperty("measure-performance")
      measurePerformance: Boolean = false,
    @JsonProperty("run-tests")
      runTests: Boolean = true,
    options: Seq[String] = Seq.empty,
    projects: Seq[String] = Seq.empty // if empty -> build all projects (default)
)
