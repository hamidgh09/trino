@Library("jenkins-library@main")

import com.logicalclocks.jenkins.k8s.ImageBuilder

node("local") {
    stage('Clone repository') {
      checkout scm
    }

    // Set defaults via env (scripted pipeline doesn't support a declarative `environment` block)
    env.ARCH = 'amd64'
    env.TRINO_VERSION = ''
    env.TAG_PREFIX = 'trino'
    env.SERVER_ARTIFACT = 'trino-server'
    env.SKIP_TESTS = 'false'
    env.WORK_DIR = 'core/docker'
    // Build against a private, per-workspace Maven repo (wiped every build) so
    // stale or locally-installed jars in the node's ~/.m2 can never leak in
    env.MAVEN_LOCAL_REPO = "${env.WORKSPACE}/.m2/repository"

    stage('Init') {
        // start every build from a clean local Maven repo
        sh "rm -rf \"${env.MAVEN_LOCAL_REPO}\""

        env.TRINO_VERSION = sh(script: "./mvnw -f pom.xml --quiet -Dmaven.repo.local=\"${env.MAVEN_LOCAL_REPO}\" help:evaluate -Dexpression=project.version -DforceStdout", returnStdout: true).trim()

        // JDK version is now defined as <temurin.release> in pom.xml
        env.JDK_RELEASE = sh(script: "./mvnw -f pom.xml --quiet -Dmaven.repo.local=\"${env.MAVEN_LOCAL_REPO}\" help:evaluate -Dexpression=temurin.release -DforceStdout", returnStdout: true).trim()

        // Construct the Adoptium download URL from the release name and architecture
        env.JDK_DOWNLOAD_LINK = "https://api.adoptium.net/v3/binary/version/${env.JDK_RELEASE}/linux/x64/jdk/hotspot/normal/eclipse?project=jdk"

        echo "TRINO_VERSION=${env.TRINO_VERSION}"
        echo "JDK_RELEASE=${env.JDK_RELEASE}"
        echo "JDK_DOWNLOAD_LINK=${env.JDK_DOWNLOAD_LINK}"
    }

    stage('Maven Build') {
        // ensure the wrapper is present and executable
        sh "test -f ${env.WORKSPACE}/mvnw || (echo 'mvnw not found' && exit 1)"
        sh "chmod +x ${env.WORKSPACE}/mvnw"

        sh "wget -O jdk24.tar.gz \"${env.JDK_DOWNLOAD_LINK}\""
        sh "mkdir -p ${env.WORKSPACE}/jdk"
        sh "tar -xzf jdk24.tar.gz -C ${env.WORKSPACE}/jdk --strip-components=1"
        sh "rm jdk24.tar.gz"

        sh "JAVA_HOME=${env.WORKSPACE}/jdk ${env.WORKSPACE}/mvnw clean package -DskipTests -Dmaven.repo.local=\"${env.MAVEN_LOCAL_REPO}\""

        // Archive artifacts
        archiveArtifacts artifacts: "core/${env.SERVER_ARTIFACT}/target/${env.SERVER_ARTIFACT}-${env.TRINO_VERSION}.tar.gz", fingerprint: true, allowEmptyArchive: true
        archiveArtifacts artifacts: "client/trino-cli/target/trino-cli-${env.TRINO_VERSION}-executable.jar", fingerprint: true, allowEmptyArchive: true
    }

    stage('Build and push trino') {
      withCredentials([usernamePassword(credentialsId: 'a0770738-4ef3-4acc-a6ba-097ee6c85b44', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
        // copy artifacts into workspace
        sh """#!/usr/bin/env bash
          set -euo pipefail

          cp -f \"core/${env.SERVER_ARTIFACT}/target/${env.SERVER_ARTIFACT}-${env.TRINO_VERSION}.tar.gz\" \"${env.WORK_DIR}/\"
          cp -f \"client/trino-cli/target/trino-cli-${env.TRINO_VERSION}-executable.jar\" \"${env.WORK_DIR}/trino-cli.jar\"
          tar -C \"${env.WORK_DIR}\" -xzf \"${WORK_DIR}/${env.SERVER_ARTIFACT}-${env.TRINO_VERSION}.tar.gz\"
          rm -f \"${env.WORK_DIR}/${env.SERVER_ARTIFACT}-${env.TRINO_VERSION}.tar.gz\"

          # Ensure the destination does not exist to avoid 'Directory not empty' errors
          if [ -d "${env.WORK_DIR}/trino-server" ]; then
            echo "Removing existing `trino-server` directory"
            rm -rf "${env.WORK_DIR}/trino-server"
          fi

          mv \"${env.WORK_DIR}/${env.SERVER_ARTIFACT}-${env.TRINO_VERSION}\" \"${WORK_DIR}/trino-server\"

          cp -R core/docker/bin \"${env.WORK_DIR}/trino-server\"
          # same file core/docker == > WORK_DIR, so no need to copy
          # cp -R core/docker/default \"${env.WORK_DIR}/\"
        """
        version = readFile "version"
        withEnv(["TAG_VERSION=${env.TRINO_VERSION}.${version.trim()}", "JDK_RELEASE=${env.JDK_RELEASE}", "JDK_DOWNLOAD_LINK=${env.JDK_DOWNLOAD_LINK}", "ARCH=${env.ARCH}"]) {
          def builder = new ImageBuilder(this)
          def m = readFile "${env.WORKSPACE}/build-manifest.json"
          builder.run(m)
        }
      }
    }
}
