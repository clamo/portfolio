SERVICES = [];
LOCK_PREFIX="integration-test-";

pipeline {

  options {
    buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '30', daysToKeepStr: '30', artifactDaysToKeepStr: '30'))
    disableConcurrentBuilds()
    throttle(['integration_test'])
  }

  triggers {
    cron('H */3 * * *')
  }

  parameters {
    string(name: 'BRANCH', defaultValue: 'develop', description: 'Branch name')
  }

  environment {
    TSID_CLI_AWS_DEFAULT_REGION   = 'us-west-2'
    TSID_CLI_ROOT         = '${env.WORKSPACE}/scripts'
    TSID_ENVIRONMENT      = 'stage'
    TSID_CLI_SWAGGER_ROOT = 'unused'
    PATH="${env.WORKSPACE}/scripts:/root/.local/bin:$PATH"
    AWS_ACCESS_KEY_ID     = credentials('jenkins-aws-secret-key-id')
    AWS_SECRET_ACCESS_KEY = credentials('jenkins-aws-secret-access-key')
    AWS_DEFAULT_REGION    = credentials('jenkins-aws-region')
  }

agent {
  kubernetes {
    defaultContainer 'jnlp'
    yamlFile './podsetup/jenkins-setup-large-deploy.yaml'
    }
  }

  stages {git
    stage('Checkout cloud.git') {
      steps {
        container('cidcloud14') {
          withCredentials([usernamePassword(credentialsId: 'github-cidmin-https', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
            dir("${env.WORKSPACE}") {
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
                      $class: 'LocalBranch',
                      localBranch: "**"
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
              script {
                SERVICES = sh(
                  script: "jq -r  '.scripts | select(has(\"test:integration\")) | input_filename' services/*/v3.0/src/package.json | cut -d / -f 2",
                  returnStdout: true
                ).split("\\s+");
                SIZE = SERVICES.size();
                echo "Detected ${SIZE} services to test: ${SERVICES}"
              }
            }
          }
        }
      }
    }

    stage('Build and test') {
      steps {
        container('cidcloud14') {
          script {
            dir("${env.WORKSPACE}") {
              stage('Install root packages') {
                sh script: "ls -l"
                sh script: "npm -v"
                sh script: "node -v"
                sh script: "tsc -v"
                sh script: "gulp -v"
                sh script: "npm install"
              }
              stage('Build common libraries') {
                sh script: "npm run build:libs"
              }
              SERVICES.each() { service ->
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                  stage("Build+test: ${service}") {
                    lock(resource: "${LOCK_PREFIX}${service}") {
                      dir("${env.WORKSPACE}/services/${service}/v3.0/src") {
                        sh script: "npm run build:service"
                        sh script: "npm run test:int"
                        sh script: "rm -rf node_modules/ ../dist/"
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

  }
}
