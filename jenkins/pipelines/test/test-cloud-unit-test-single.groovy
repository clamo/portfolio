CLONE_DEPTH = 20;
LOCK_PREFIX="unit-test-";

pipeline {

  options {
    buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '30', daysToKeepStr: '30', artifactDaysToKeepStr: '30'))
    lock(resource: "${LOCK_PREFIX}${params.service}")
    timeout(time: 25, unit: 'MINUTES')
}

  parameters {
    string(name: 'BRANCH', defaultValue: 'develop', description: 'Branch name')
    string(name: 'SLACKCHANNEL', defaultValue: '#builds-dev-stage', description: '#channel name or @slackid')
    choice(name: 'service', description: 'Which service?', choices: ['serviceName1', 'serviceName2', 'serviceName3'])
  }

  environment {
    TSID_CLI_AWS_DEFAULT_REGION   = 'us-west-2'
    TSID_CLI_ROOT         = '${env.WORKSPACE}/scripts'
    TSID_ENVIRONMENT      = 'dev'
    TSID_CLI_SWAGGER_ROOT = 'unused'
    PATH="${env.WORKSPACE}/scripts:/root/.local/bin:$PATH"
    AWS_ACCESS_KEY_ID     = credentials('jenkins-aws-secret-key-id')
    AWS_SECRET_ACCESS_KEY = credentials('jenkins-aws-secret-access-key')
    AWS_DEFAULT_REGION    = credentials('jenkins-aws-region')
  }

agent {
  kubernetes {
    defaultContainer 'jnlp'
    yamlFile './podsetup/jenkins-setup-small-deploy.yaml'
    }
  }

  stages {

    stage('Preload') {
      steps {
        script {
          dir("${env.WORKSPACE}") {
            checkout scm
            preload=load "preload.groovy"
          }
        }
      }
    }

    stage('Checkout cloud.git') {
      steps {
        container('cidcloud14') {
          withCredentials([usernamePassword(credentialsId: 'github-cidmin-https', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
            dir("${env.WORKSPACE}/cloud") {
              checkout changelog: false,
                poll: false,
                scm: [
                  $class: 'GitSCM',
                  branches: [[name: params.BRANCH]],
                  extensions: [[
                      $class: 'CloneOption',
                      honorRefspec: true,
                      noTags: true,
                      reference: '',
                      depth: CLONE_DEPTH,
                      shallow: true
                    ], [
                      $class: 'CleanBeforeCheckout',
                      deleteUntrackedNestedRepositories: true
                  ]],
                  userRemoteConfigs: [[
                    credentialsId: 'github-cidmin-https',
                    refspec: '+refs/heads/*:refs/remotes/origin/*',
                    url: 'https://github.com/my-company/cloud.git'
                  ]
                ]
              ]
            }
          }
        }
      }
    }

    stage('private package access') {
      steps {
        container('cidcloud14') {
          dir("${env.WORKSPACE}") {
            withCredentials([string(credentialsId: 'npmrc', variable: 'NPM_TOKEN')]) {
              sh script: "echo //npm.pkg.github.com/:_authToken=${NPM_TOKEN} > .npmrc"
            }
          }
        }
      }
    }

    stage('Preparing Integration Test - npm install') {
      steps {
        catchError(buildResult: null, stageResult: 'FAILURE') {
          container('cidcloud14') {
            dir("${env.WORKSPACE}/cloud") {
              sh script: "npm install"
            }
          }
        }
      }
    }

    stage('gulp compile-libs') {
      steps {
        catchError(buildResult: null, stageResult: 'FAILURE') {
          container('cidcloud14') {
            dir("${env.WORKSPACE}/cloud") {
              sh script: "gulp compile-libs | tee ${env.WORKSPACE}/output.log; exit \${PIPESTATUS[0]}"
            }
          }
        }
      }
    }

    stage('npm run build') {
      steps {
        catchError(buildResult: null, stageResult: 'FAILURE') {
          container('cidcloud14') {
            dir("${env.WORKSPACE}/cloud/services/${params.service}/v3.0/src") {
              catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                script {
                  stage ("Build: ${params.service}") {
                    dir("${env.WORKSPACE}/cloud/services/${params.service}/v3.0/src") {
                      CURRENT_STAGE_NAME="Build: ${params.service}"
                      def exitCode = sh script: "npm run build | tee ${env.WORKSPACE}/output.log; exit \${PIPESTATUS[0]}", returnStatus: true
                      if (exitCode != 0) {
                        script {
                          preload.getFailedLogs()
                        }
                        error()
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    stage('Run Unit Test') {
      steps {
        catchError(buildResult: null, stageResult: 'FAILURE') {
          container('cidcloud14') {
            dir("${env.WORKSPACE}/cloud/services/${params.service}/v3.0/dist/test") {
              catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                script {
                  stage ("Test: ${params.service}") {
                    dir("${env.WORKSPACE}/cloud/services/${params.service}/v3.0/src") {
                      CURRENT_STAGE_NAME="Test: ${params.service}"
                      def exitCode = sh script: "npm run test --showlogs | tee ${env.WORKSPACE}/output.log; exit \${PIPESTATUS[0]}", returnStatus: true
                      if (exitCode != 0) {
                        script {
                          preload.getFailedMochaLogs()
                        }
                        error()
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    stage('Determine Pass/Fail') {
      steps {
        catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
          script {
            preload.determinePassFail()
          }
        }
      }
    }

  }

  post {
    always {
      script {
        CONSOLE_LOG = "${env.BUILD_URL}console"
        JOBLINK = "${env.JOB_URL}"
        BUILD_STATUS = currentBuild.currentResult
        preload.createHtmlReport()
        preload.sendSlackNotifcation()
      }
    }
  }

}