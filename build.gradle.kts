// Build for the open-source Zurp repository. Inside Meta's monorepo the build is BUCK; this
// file is what lets anyone else compile the extension.
//
// Not yet verified end to end -- it was written where Maven Central is unreachable, so the
// dependency resolution has never actually run. Treat a failure here as a bug worth reporting
// rather than something wrong on your side.

plugins {
  java
}

repositories {
  mavenCentral()
}

java {
  // Deliberately not a toolchain: that makes Gradle demand a JDK of exactly this version be
  // installed and fail outright if it is not. Targeting the release instead compiles against the
  // Java 17 API using whatever JDK is running Gradle, which is 17 or newer by definition.
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
  options.release = 17
}

dependencies {
  // Pinned to the oldest Burp the README claims support for, so the compiler enforces that floor
  // rather than us discovering at runtime that a researcher on 2024.7 hits a missing method.
  // Burp provides this at runtime, so it is compile-only and must not be bundled: a second
  // copy of the API in the jar would be loaded in preference to Burp's own.
  compileOnly("net.portswigger.burp.extensions:montoya-api:2024.7")

  testImplementation("net.portswigger.burp.extensions:montoya-api:2024.7")
  testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
  main {
    java.setSrcDirs(listOf("burp"))
  }
  test {
    java.setSrcDirs(listOf("test"))
  }
}

tasks.jar {
  archiveFileName = "zurp.jar"
}

tasks.test {
  useJUnitPlatform()
}
