//if (currentBuild.previousBuild == null) { // first build, should configure job
    properties([
        [$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', daysToKeepStr: '60', numToKeepStr: '60', artifactNumToKeepStr: '1']],
        disableConcurrentBuilds(),
        [$class: 'GithubProjectProperty', displayName: '', projectUrlStr: 'https://github.com/nuxeo/nuxeo/'],
        [$class: 'RebuildSettings', autoRebuild: false, rebuildDisabled: false]])
//}

node('SLAVE') {
    
    def sha = stage('clone') {
        checkout(
            [$class: 'GitSCM',
             branches: [[name: '*/${BRANCH_NAME}']],
             browser: [$class: 'GithubWeb', repoUrl: 'https://github.com/nuxeo/nuxeo'],
             doGenerateSubmoduleConfigurations: false,
             extensions: [
                    [$class: 'CleanBeforeCheckout'],
                    [$class: 'CloneOption', noTags: true, reference: '', shallow: false]
                ],
             submoduleCfg: [],
             userRemoteConfigs: [[url: 'git@github.com:nuxeo/nuxeo.git']]
            ])
        stash('ws')
        def originsha = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
        return originsha
    }

    stage('rebase') {
        withBuildStatus('rebase', 'https://github.com/nuxeo/nuxeo', sha, "${BUILD_URL}") {
            sh "./clone.py $BRANCH_NAME -f master --rebase"
        }
    }
    
    stage('compile') {
        withBuildStatus('compile', 'https://github.com/nuxeo/nuxeo', sha, "${BUILD_URL}") {
            withMaven() {
                sh 'mvn -T1.5C -B install -Pqa,addons -DskipTests'
            }
        }
    }

    stage('validate') {
        withBuildStatus('validate', 'https://github.com/nuxeo/nuxeo', sha, "${BUILD_URL}") {
            withMaven() {
                sh 'mvn -B test validate install -Pqa,addons -Dmaven.test.failure.ignore=true -Dnuxeo.tests.random.mode=STRICT'
            }
        }
    }

    stage('postgresql') {
        verifyProfile('pgsql', '9.6')
    }

    stage('mongo') {
        verifyProfile('mongodb', '3.4')
    }

    stage('kafka') {
        verifyProfile('kafka', '1.0')
    }
}

def verifyProfile(String profile, String version) {
    withEnv(["DBPROFILE=$profile", "DBVERSION=$version"]) {
        parallel(
            'cmis' : emitVerifyClosure(nodelabel, sha, zipfile, 'cmis', 'nuxeo-server-cmis-tests') {
                archive 'nuxeo-distribution/nuxeo-server-cmis-tests/target/**/failsafe-reports/*, nuxeo-distribution/nuxeo-server-cmis-tests/target/*.png, nuxeo-distribution/nuxeo-server-cmis-tests/target/*.json, nuxeo-distribution/nuxeo-server-cmis-tests/target/**/*.log, nuxeo-distribution/nuxeo-server-cmis-tests/target/**/log/*, nuxeo-distribution/nuxeo-server-cmis-tests/target/**/nxserver/config/distribution.properties, nuxeo-distribution/nuxeo-server-cmis-tests/target/nxtools-reports/*'
                failOnServerError('nuxeo-distribution/nuxeo-server-cmis-tests/target/tomcat/log/server.log')
            },
            'webdriver' : emitVerifyClosure(nodelabel, sha, zipfile, 'webdriver', 'nuxeo-jsf-ui-webdriver-tests') {
                archive 'nuxeo-distribution/nuxeo-jsf-ui-webdriver-tests/target/**/failsafe-reports/*, nuxeo-distribution/nuxeo-jsf-ui-webdriver-tests/target/*.png, nuxeo-distribution/nuxeo-jsf-ui-webdriver-tests/target/*.json, nuxeo-distribution/nuxeo-jsf-ui-webdriver-tests/target/**/*.log, nuxeo-distribution/nuxeo-jsf-ui-webdriver-tests/target/**/log/*, nuxeo-distribution/nuxeo-jsf-ui-webdriver-tests/target/**/nxserver/config/distribution.properties, nuxeo-distribution/nuxeo-server-cmis-tests/target/nxtools-reports/*, nuxeo-distribution/nuxeo-jsf-ui-webdriver-tests/target/results/*/*'
                junit '**/target/surefire-reports/*.xml, **/target/failsafe-reports/*.xml, **/target/failsafe-reports/**/*.xml'
                failOnServerError('nuxeo-distribution/nuxeo-jsf-ui-webdriver-tests/target/tomcat/log/server.log')
            }
        )
    }
}

def emitVerifyClosure(String nodelabel, String sha, String zipfile, String name, String dir, Closure post) {
    return {
        node(nodelabel) {
            stage(name) {
                ws("${WORKSPACE}-${name}") {
                    unstash 'ws'
                    def mvnopts = zipfile != "" ? "-Dzip.file=${WORKSPACE}/${zipfile}" : ""
                    timeout(time: 2, unit: 'HOURS') {
                        withBuildStatus("${DBPROFILE}-${DBVERSION}/ftest/${name}", 'https://github.com/nuxeo/nuxeo', sha, "${BUILD_URL}") {
                            withDockerCompose("${JOB_NAME}-${BUILD_NUMBER}-${name}", "integration/Jenkinsfiles/docker-compose-${DBPROFILE}-${DBVERSION}.yml", "mvn ${mvnopts} -B -f ${WORKSPACE}/nuxeo-distribution/${dir}/pom.xml -Pqa,tomcat,${DBPROFILE} clean verify", post)
                        }
                    }
                }
            }
        }
    }
}
