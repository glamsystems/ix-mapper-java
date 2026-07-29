plugins {
  id("software.sava.build.feature.publish-maven-central")
}

dependencies {
  nmcpAggregation(project(":ix-proxy"))
}

tasks.register("publishToGitHubPackages") {
  group = "publishing"
  dependsOn(
    ":ix-proxy:publishMavenJavaPublicationToSavaGithubPackagesPublishRepository"
  )
}
