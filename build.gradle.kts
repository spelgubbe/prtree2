import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.testing.Test

plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

sourceSets {
    main {
        java {
            setSrcDirs(listOf("src"))
            exclude("org/khelekore/prtree/junit/**")
        }
    }
    test {
        java {
            setSrcDirs(listOf("src"))
            include("org/khelekore/prtree/junit/**")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 17
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val historicalTests = listOf(
    "org.khelekore.prtree.junit.TestPRTree.testMany",
    "org.khelekore.prtree.junit.TestPRTree.testFindSpeed",
    "org.khelekore.prtree.junit.TestPRTree.testUniformDatasetQueryPerf",
    "org.khelekore.prtree.junit.TestPRTree.testAdversarialDatasetQueryPerf",
    "org.khelekore.prtree.junit.TestPRTree.testAdversarialUpperLeftCornerClusterDatasetQueryPerf",
    "org.khelekore.prtree.junit.TestPRTree.testBuildSerialEqualRects",
    "org.khelekore.prtree.junit.TestPRTree.testBuildParallelEqualRects",
    "org.khelekore.prtree.junit.TestPRTree.testBuildOriginalEqualRects",
    "org.khelekore.prtree.junit.TestPRTree.testBuildSerialRandomRects",
    "org.khelekore.prtree.junit.TestPRTree.testBuildParallelRandomRects",
    "org.khelekore.prtree.junit.TestPRTree.testBuildOriginalRandomRects",
)

tasks.test {
    useJUnit()
    filter {
        historicalTests.forEach(::excludeTestsMatching)
    }
}

tasks.register<Test>("historicalTest") {
    description = "Runs the original long-running and hand-timed test cases."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnit()
    filter {
        historicalTests.forEach(::includeTestsMatching)
    }
    shouldRunAfter(tasks.test)
}
