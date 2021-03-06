package com.typesafe.dbuild.model

import org.specs2.mutable.Specification
import Utils.{writeValue,readValue}

object ExtractedBuildMetaSpec extends Specification {

  "ExtractedBuildMeta" should {
    "parse metadata file" in {
      
      readValue[ExtractedBuildMeta](
"""{
  "proj-info" = [{
  version = "1.0",
  projects = [{
          name = "p1"
          organization = "o1"
          artifacts = [{
            name = "p1"
            organization = "o1"
            extension = "jar"
          }]
          dependencies = []
    }]
  subproj = ["hey"] }]
}""") must equalTo(ExtractedBuildMeta(
    Seq(ProjMeta("1.0",Seq(Project("p1", "o1", Seq(ProjectRef("p1", "o1", "jar")), Seq.empty)), Seq("hey")))))
    }
    
    "parse pretty printed metadata" in {
      val data = 
        ExtractedBuildMeta(Seq(ProjMeta("1.0", 
          Seq(
              Project("p1", "o1", Seq(ProjectRef("p1", "o1")), Seq.empty),
              Project("p2", "o1", Seq(ProjectRef("p2", "o1")), 
                  Seq(
                    ProjectRef("p3", "o2"),
                    ProjectRef("p4", "o3")
                  ))),Seq("hey"))))
      val config = writeValue(data)
      (readValue[ExtractedBuildMeta](config) 
          must 
          equalTo(data))
    }
  }
}