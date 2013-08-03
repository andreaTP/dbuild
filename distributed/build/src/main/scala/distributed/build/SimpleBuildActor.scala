package distributed
package build

import project.model._
import project.build._
import DeployBuild._
import repo.core.{Repository,LocalRepoHelper}
import project.dependencies.ExtractBuildDependencies
import logging.Logger
import akka.actor.{Actor,ActorRef,Props}
import akka.pattern.{ask,pipe}
import akka.dispatch.{Future,Futures}
import akka.util.duration._
import akka.util.Timeout
import actorpaterns.forwardingErrorsToFutures
import sbt.Path._
import java.io.File
import distributed.repo.core.ProjectDirs

case class RunDistributedBuild(build: DistributedBuildConfig, target: File, logger: Logger)

// Very simple build actor that isn't smart about building and only works locally.
class SimpleBuildActor(extractor: ActorRef, builder: ActorRef, repository: Repository) extends Actor {
  def receive = {
    case RunDistributedBuild(build, target, log) => forwardingErrorsToFutures(sender) {
      val listener = sender
      val logger = log.newNestedLogger(hashing sha1 build)
      // "build" contains the project configs as written in the configuration file.
      // Their 'extra' field could be None, or contain information that must be completed
      // according to the build system in use for that project.
      // Only each build system knows its own defaults (which may change over time),
      // therefore we have to ask to the build system itself to expand the 'extra' field
      // as appropriate.
      checkDeployFullBuild(build.deploy)
      val result = for {
        fullBuild <- analyze(build, target, log.newNestedLogger(hashing sha1 build))
        fullLogger = log.newNestedLogger(fullBuild.uuid)
        _ = publishFullBuild(fullBuild, fullLogger)
        arts <- runBuild(target, fullBuild, fullLogger)
        _ = printReport(arts, fullLogger)
        _ = deployFullBuild(fullBuild)
      } yield arts
      result pipeTo listener
    }
  }
  
  /** Publishing the full build to the repository and logs the output for
   * re-use.
   */
  def publishFullBuild(build: RepeatableDistributedBuild, log: Logger): Unit = {
    log.info("---==  RepeatableBuild ==---")
    log.info(" uuid = " + build.uuid)
    log.debug("---==   Repeatable Build Config   ===---")
    log.debug(build.repeatableBuildString)
    log.debug("---== End Repeatable Build Config ===---")
    log.info("---== Dependency Information ===---")
    build.repeatableBuilds foreach { b =>
      log.info("Project "+ b.config.name)
      log.info(b.dependencies.map{_.config.name}mkString("  depends on: ", ", ",""))
    }
    log.info("---== End Dependency Information ===---")
    LocalRepoHelper.publishBuildMeta(build, repository)
  }

  def printReport(outcomes: Seq[(RepeatableProjectBuild, BuildOutcome)], log: Logger) = {
    log.info("---==  Execution Report ==---")
    outcomes foreach {
      case (project, outcome) =>
        val msg = "Project " + project.config.name + ": " + (outcome match {
          case BuildSuccess(info, false) => "SUCCESS"
          case BuildSuccess(info, true) => "SUCCESS (did not need to be rebuilt)"
          case BuildFailed(cause) => "**** FAILED **** --- compilation failed: "+cause
          case BuildDidNotRun(outs) => "DID NOT RUN (stuck on broken dependency: " +
            (outs.filter { case (_,_:BuildFailed) => true; case _ => false }).map{_._1.config.name}.mkString(",") + ")"
        })
        log.info(msg)
    }
    log.info("---==  End Execution Report ==---")
  }

  def logPoms(build: RepeatableDistributedBuild, arts: BuildArtifactsIn, log: Logger): Unit = 
    try {
      log info "Printing Poms!"
      val poms = repo.PomHelper.makePomStrings(build, arts)
      log info (poms mkString "----------")
    } catch {
      case e: Throwable => 
        log trace e
        throw e
    }
  
  implicit val buildTimeout: Timeout = 4 hours

  // Chain together some Asynch to run this build.
  def runBuild(target: File, build: RepeatableDistributedBuild, log: Logger): Future[Seq[(RepeatableProjectBuild, BuildOutcome)]] = {
    implicit val ctx = context.system
    val tdir = ProjectDirs.targetDir
    type State = Future[(RepeatableProjectBuild, BuildOutcome)]
    def runBuild(builds: RepeatableDistributedBuild): Seq[State] =
      builds.graph.traverse { (children: Seq[State], p: ProjectConfigAndExtracted) =>
        val b = builds.buildMap(p.config.name)
        Future.sequence(children) flatMap { outcomes =>
          if (outcomes exists { case (_, _: BuildFailure) => true; case (_, _: BuildSuccess) => false }) {
            Future((b, BuildDidNotRun(outcomes)))
          } else {
            val outProjects = p.extracted.projects
            buildProject(tdir, b, outProjects, log.newNestedLogger(b.config.name)) map { (b, _) }
          }
        }
      }(Some((a, b) => a.config.name < b.config.name))

    // TODO - REpository management here!!!!
    ProjectDirs.userRepoDirFor(build) { localRepo =>
      // we go from a Seq[Future[BuildOutcome]] to a Future[BuildOutcome]
      Future.sequence(runBuild(build))
    }
  }  
  
  // Asynchronously extract information from builds.
  def analyze(config: DistributedBuildConfig, target: File, log: Logger): Future[RepeatableDistributedBuild] = {
    implicit val ctx = context.system
    val uuid = hashing sha1 config
    val tdir = target / "extraction" / uuid
    val builds: Future[Seq[ProjectConfigAndExtracted]] = 
      Future.traverse(config.projects)(extract(tdir, log))
    // We don't have to do ordering here anymore.
    builds map {RepeatableDistributedBuild(_, config.deploy, config.buildOptions)}
  }

  // Our Asynchronous API.
  def extract(target: File, logger: Logger)(config: ProjectBuildConfig): Future[ProjectConfigAndExtracted] =
    (extractor ? ExtractBuildDependencies(config, target, logger.newNestedLogger(config.name))).mapTo[ProjectConfigAndExtracted]
  
  
  // TODO - Repository Knowledge here
  // outProjects is the list of Projects that will be generated by this build, as reported during extraction.
  // we will need it to calculate the version string in LocalBuildRunner, but won't need it any further 
  def buildProject(target: File, build: RepeatableProjectBuild, outProjects: Seq[Project], logger: Logger): Future[BuildOutcome] =
     (builder ? RunBuild(target, build, outProjects, logger)).mapTo[BuildOutcome]
}