CLONE_DEPTH = 20;
patchedServiceVersion = "UNKNOWN"
SERVICES = [];
SERVICESPULUMIFIED = [];
SERVICESK8S = [];

pipeline {
  options {
    buildDiscarder(logRotator(numToKeepStr: '50', artifactNumToKeepStr: '50', daysToKeepStr: '30', artifactDaysToKeepStr: '30'))
  }
  agent {
    kubernetes {
      defaultContainer 'jnlp'
      yamlFile './podsetup/jenkins-setup-large-deploy.yaml'
    }
  }
  parameters {
    string(name: 'BRANCH', defaultValue: 'develop', description: 'Branch name')
    choice(name: 'RUNENV', description: 'Which environment?', choices: ['dev', 'stage'])
    choice(name: 'FEATURESLOT', description: 'Which stage/slot?', choices: ['f1', 'f2', 'f3', 'f4', 'f5', 'f6', 'f7', 'f8', 'f9', 'f10', 'f11', 'f12', 'f13', 'f14', 'f15', 'f16', 'f17', 'f18', 'f19', 'f20', 'f21', 'f22', 'f23', 'f24', 'stable'])
    choice(name: 'CID_LOG_LEVEL', description: 'Set log level', choices: ['ERROR', 'WARN', 'INFO', 'VERBOSE'])
  }
  environment {
    TSID_CLI_AWS_REGION   = 'us-west-2'
    TSID_CLI_ROOT         = '${env.WORKSPACE}/cloud/scripts'
    TSID_ENVIRONMENT      = 'dev'
    TSID_CLI_SWAGGER_ROOT = 'unused'
    PATH="/root/.local/bin:/root/.pulumi/bin:$PATH"
    AWS_ACCESS_KEY_ID     = credentials('jenkins-aws-secret-key-id')
    AWS_SECRET_ACCESS_KEY = credentials('jenkins-aws-secret-access-key')
    AWS_DEFAULT_REGION    = credentials('jenkins-aws-region')
    PULUMI_ACCESS_TOKEN   = credentials('pulumi-token')
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
        container('dndc') {
          withCredentials([usernamePassword(credentialsId: 'github-cidmin-https', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
            dir("${env.WORKSPACE}/cloud") {
              // Checkout the workspace
              // command generated via http://jenkins.centercard.us/pipeline-syntax/
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
                  // Only needed if we want to build PR branches:
                  // ], [
                  //   credentialsId: 'github-cidmin-https',
                  //   refspec: '+refs/pull/*:refs/remotes/origin/pr/*',
                  //   url: 'https://github.com/my-company/cloud.git'
                  ]
                ]
              ]
              // Identify services that have a test:integration build step
              script {
                preload.autoDetectPulumifiedServices()
                preload.autoDetectK8sServices()
                preload.autoDetectAllServices()
              }
            }
          }
        }
      }
    }

    stage('Set credentials') {
      steps {
        container('dndc') {
          script {
            sh script: "aws --profile prod-admin configure set aws_access_key_id ${AWS_ACCESS_KEY_ID}"
            sh script: "aws --profile prod-admin configure set aws_secret_access_key ${AWS_SECRET_ACCESS_KEY}"
            sh script: "aws --profile prod-admin configure set region 'us-west-2'"
            withCredentials([usernamePassword(credentialsId: 'jenkins-aws-dev-id-key', usernameVariable: 'DEV_ID', passwordVariable: 'DEV_KEY')]) {
              sh script: "aws --profile dev-admin configure set aws_access_key_id ${DEV_ID}"
              sh script: "aws --profile dev-admin configure set aws_secret_access_key ${DEV_KEY}"
              sh script: "aws --profile dev-admin configure set region 'us-west-2'"
            withCredentials([usernamePassword(credentialsId: 'jenkins-aws-stage-id-key', usernameVariable: 'STAGE_ID', passwordVariable: 'STAGE_KEY')]) {
                sh script: "aws --profile stage-admin configure set aws_access_key_id ${STAGE_ID}"
                sh script: "aws --profile stage-admin configure set aws_secret_access_key ${STAGE_KEY}"
                sh script: "aws --profile stage-admin configure set region 'us-west-2'"
              }
            }
          }
        }
      }
    }

    stage('Install packages') {
      steps {
        container('dndc') {
          dir("${env.WORKSPACE}/cloud") {
            sh script: "npm install"
            sh script: "curl -fsSL https://get.pulumi.com | sh"
            sh script: "pulumi version"
          }
        }
      }
    }

    stage('Build, test, and deploy service') {
      steps {
        script {
          SERVICES.each { service ->
            lock(resource: "${service}-${params.RUNENV}") {
              stage (service) {
                container('dndc') {
                  catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    if (SERVICESK8S.contains(service)) {
                      stage ("Skipping K8s Service: ${service}") {}
                    }
                    dir("${env.WORKSPACE}/cloud/services/${service}/v3.0/src") {
                      if (service == "cards") {
                        sh script: "npm run build:libs"
                      }
                      if (env.RUNENV == 'stage' && params.FEATURESLOT == 'stable') {
                        script {
                          patchedServiceVersion = sh(script: "../../../../scripts/package-version patch", returnStdout: true).toString().trim()
                        }
                        dir("${env.WORKSPACE}/cloud") {
                          withCredentials([usernamePassword(credentialsId: 'github-cidmin-https', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
                            sh script: """git commit -a -m 'Jenkins deploy ${service} to ${patchedServiceVersion}'"""
                            sh script: "git pull --rebase https://${GIT_USERNAME}:${GIT_PASSWORD}@github.com/my-company/cloud.git ${params.BRANCH}"
                            sh script: "git push https://${GIT_USERNAME}:${GIT_PASSWORD}@github.com/my-company/cloud.git"
                          }
                        }
                      }
                      sh script: "npm run build:service"
                    }
                    if (SERVICESPULUMIFIED.contains(service) && params.RUNENV == 'dev') {
                      dir("${env.WORKSPACE}/cloud/services/${service}/v3.0/src") {
                        sh script: "npm run package"
                      }
                      dir("${env.WORKSPACE}/cloud/services/${service}/v3.0/pulumi") {
                        sh script: "cp ${env.WORKSPACE}/cloud/.npmrc .npmrc"
                        sh script: "npm install"
                        sh script: "pulumi stack select -s center/prod-${params.RUNENV}"
                        sh script: "export CID_DEPLOY_SLOT=${params.FEATURESLOT} && pulumi up -y"
                        sh script: "pulumi stack select -s center/${params.RUNENV}"
                        sh script: "export CID_DEPLOY_SLOT=${params.FEATURESLOT} && pulumi up -y"
                      }
                    }
                    if (SERVICESPULUMIFIED.contains(service) && params.RUNENV == 'stage') {
                      dir("${env.WORKSPACE}/cloud/services/${service}/v3.0/src") {
                        sh script: "npm run package"
                      }
                      dir("${env.WORKSPACE}/cloud/services/${service}/v3.0/pulumi") {
                        sh script: "cp ${env.WORKSPACE}/cloud/.npmrc .npmrc"
                        sh script: "npm install"
                        sh script: "pulumi stack select -s center/prod-${params.RUNENV}"
                        sh script: "export CID_DEPLOY_SLOT=${params.FEATURESLOT} && pulumi up -y"
                      }
                      // center/prod stack is not ready as of today.
                      // dir("${env.WORKSPACE}/cloud/services/${service}/v3.0/pulumi") {
                      //   sh script: "pulumi stack select -s center/${params.RUNENV}"
                      //   sh script: "export CID_DEPLOY_SLOT=${params.FEATURESLOT} && pulumi up -y"
                      // }
                    } 
                    if (!SERVICESPULUMIFIED.contains(service)) {
                      catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        dir("${env.WORKSPACE}/cloud/services/${service}/v3.0/src") {
                          sh script: """CID_LOG_LEVEL=${params.CID_LOG_LEVEL}"""
                          sh script: "npm run package"
                          sh script: """npm run deploy ${params.FEATURESLOT}:${params.RUNENV}"""
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

  post {
    failure {
      slackSend (channel: '#builds-dev-stage', color: '#FF0000', message: "FAILED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' (${env.RUN_DISPLAY_URL})")
    }
  }
}