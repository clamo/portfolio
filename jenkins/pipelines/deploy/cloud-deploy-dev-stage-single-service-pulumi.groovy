CLONE_DEPTH = 20;
patchedServiceVersion = "UNKNOWN"

SERVICES = [];
SERVICESPULUMIFIED = [];

pipeline {

  options {
    buildDiscarder(logRotator(numToKeepStr: '50', artifactNumToKeepStr: '50', daysToKeepStr: '30', artifactDaysToKeepStr: '30'))
    lock(resource: "${params.service}-${params.RUNENV}")
    timeout(time: 25, unit: 'MINUTES')
  }


agent {
  kubernetes {
    defaultContainer 'jnlp'
    yamlFile './podsetup/jenkins-setup-large-deploy'
    }
  }

  parameters {
    string(name: 'BRANCH', defaultValue: 'develop', description: 'Branch name')
    choice(name: 'service', description: 'Which service?', choices: ['serviceName1', 'serviceName2', 'serviceName3'])
    choice(name: 'RUNENV', description: 'Which environment?', choices: ['stage', 'dev'])    
    choice(name: 'FEATURESLOT', description: 'Which stage/slot?', choices: ['stable', 'test1','test2'])
    choice(name: 'SYNC', description: 'Sync slot with stable?', choices: ['YES', 'no'])
    choice(name: 'API_DOCS', description: 'Deploy API docs?', choices: ['YES', 'no'])
  }

  environment {
    TSID_CLI_AWS_REGION   = 'us-west-2'
    TSID_CLI_ROOT         = '${env.WORKSPACE}/cloud/scripts'
    TSID_ENVIRONMENT      = 'dev'
    TSID_CLI_SWAGGER_ROOT = 'unused'
    CID_DEPLOY_SLOT       = 'stable'
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
        container('main') {
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
              // Filter services
              script {
                preload.autoDetectAllServices()
                preload.autoDetectPulumifiedServices()
              }
            }
          }
        }
      }
    }

    stage('private package access') {
      steps {
        container('main') {
          dir("${env.WORKSPACE}") {
            withCredentials([string(credentialsId: 'npmrc', variable: 'NPM_TOKEN')]) {
             	sh script: "echo //npm.pkg.github.com/:_authToken=${NPM_TOKEN} > .npmrc"
            }
          }
        }
      }
    }

    stage('Set credentials') {
      steps {
        container('main') {
          script {
            sh script: "aws --profile prod-admin configure set aws_access_key_id ${AWS_ACCESS_KEY_ID}"
            sh script: "aws --profile prod-admin configure set aws_secret_access_key ${AWS_SECRET_ACCESS_KEY}"
            sh script: "aws --profile prod-admin configure set region 'us-west-2'"
            if (params.RUNENV == 'dev') {
              catchError(stageResult: 'FAILURE') {
                dir("${env.WORKSPACE}/cloud") {
                  withCredentials([usernamePassword(credentialsId: 'jenkins-aws-dev-id-key', usernameVariable: 'DEV_ID', passwordVariable: 'DEV_KEY')]) {
                    sh script: "aws --profile dev-admin configure set aws_access_key_id ${DEV_ID}"
                    sh script: "aws --profile dev-admin configure set aws_secret_access_key ${DEV_KEY}"
                    sh script: "aws --profile dev-admin configure set region 'us-west-2'"
                  }
                }
              }
            }
            if (params.RUNENV == 'stage') {
              catchError(stageResult: 'FAILURE') {
                dir("${env.WORKSPACE}/cloud") {
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
      }
    }

    stage('Install packages') {
      steps {
        container('main') {
          dir("${env.WORKSPACE}/cloud") {
            sh script: "npm install"
            sh script: "curl -fsSL https://get.pulumi.com | sh"
          }
        }
      }
    }

    stage('Sync slot') {
      when {
        expression { params.SYNC == 'YES' && params.FEATURESLOT != 'stable' }
      }
      steps {
        script {
          catchError(stageResult: 'FAILURE') {
            container('main') {
              dir("${env.WORKSPACE}/cloud") {
                sh script: "echo yes | npm run alias:${params.RUNENV} -- --sync --alias=${params.FEATURESLOT} --silent"
              }
            }
          }
        }
      }
    }

    stage('Build common libraries') {
      steps {
        container('main') {
          dir("${env.WORKSPACE}/cloud") {
            script {
              if (params.service == "cards") {
                sh script: "npm run build:libs"
              }
            }
          }
        }
      }
    }

    stage('Patch service version') {
      when {
          expression {
            env.RUNENV == 'stage' && params.FEATURESLOT == 'stable'
          }
      }
      steps {
        container('main') {
          dir("${env.WORKSPACE}/cloud/services/${params.service}/v3.0/src") {
            script {
              patchedServiceVersion = sh(script: "../../../../scripts/package-version patch", returnStdout: true).toString().trim()
            }
          }
          dir("${env.WORKSPACE}/cloud") {
            withCredentials([usernamePassword(credentialsId: 'github-cidmin-https', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
              sh script: """git commit -a -m 'Jenkins deploy ${params.service} to ${patchedServiceVersion}'"""
              sh script: "git pull --rebase https://${GIT_USERNAME}:${GIT_PASSWORD}@github.com/my-company/cloud.git ${params.BRANCH}"
              sh script: "git push https://${GIT_USERNAME}:${GIT_PASSWORD}@github.com/my-company/cloud.git"
            }
          }
        }
      }
    }

    stage('Build service') {
      steps {
        container('main') {
          dir("${env.WORKSPACE}/cloud/services/${params.service}/v3.0/src") {
            sh script: "npm run build:service"
            sh script: "ls -l ../dist"
          }
        }
      }
    }

    stage('Test service') {
      steps {
        container('main') {
          dir("${env.WORKSPACE}/cloud/services/${params.service}/v3.0/dist/test") {
            sh script: "npm run test --showlogs"
          }
        }
      }
    }

    stage('npm run package') {
      steps {
        container('main') {
          dir("${env.WORKSPACE}/cloud/services/${params.service}/v3.0/src") {
            sh script: "npm run package"
            sh script: "ls -l ../dist"
          }
        }
      }
    }
    
    stage('Deploy the branch into cloud slot/stage...') {
      steps {
        container('main') {
          script {
            if (SERVICESPULUMIFIED.contains(service) && params.RUNENV == 'dev') {
              dir("${env.WORKSPACE}/cloud/services/${service}/v3.0/pulumi") {
                sh script: "cp ${env.WORKSPACE}/cloud/.npmrc .npmrc"
                sh script: "npm install"
                sh script: "pulumi version"
                sh script: "pulumi stack select -s center/prod-${params.RUNENV}"
                sh script: "pulumi preview"
                sh script: "export CID_DEPLOY_SLOT=${params.FEATURESLOT} && pulumi up -y"
              }
              withAWS(region: 'us-west-2', credentials: 'aws_creds_dev') {
                dir("${env.WORKSPACE}/cloud/services/${service}/v3.0/pulumi") {
                  sh script: "pulumi stack select -s center/${params.RUNENV}"
                  sh script: "pulumi preview"
                  sh script: "export CID_DEPLOY_SLOT=${params.FEATURESLOT} && pulumi up -y"
                }
              }
            }
            if (SERVICESPULUMIFIED.contains(service) && params.RUNENV == 'stage') {
              dir("${env.WORKSPACE}/cloud/services/${service}/v3.0/pulumi") {
                sh script: "cp ${env.WORKSPACE}/cloud/.npmrc .npmrc"
                sh script: "npm install"
                sh script: "pulumi version"
                sh script: "pulumi stack select -s center/prod-${params.RUNENV}"
                sh script: "pulumi preview"
                sh script: "export CID_GIT_BRANCH=${params.BRANCH} && export CID_DEPLOY_SLOT=${params.FEATURESLOT} && pulumi up -y"
              }
              //withAWS(region: 'us-west-2', credentials: 'aws_creds_stage') {
              //  dir("${env.WORKSPACE}/cloud/services/${service}/v3.0/pulumi") {
              //    sh script: "pulumi stack select -s center/${params.RUNENV}"
              //    sh script: "pulumi preview"
              //    sh script: "export CID_GIT_BRANCH=${params.BRANCH} && export CID_DEPLOY_SLOT=${params.FEATURESLOT} && pulumi up -y"
              //  }
              //}
            } 
            if (!SERVICESPULUMIFIED.contains(service)) {
              catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {           
                dir("${env.WORKSPACE}/cloud/services/${params.service}/v3.0/src") {
                  sh script: """npm run deploy ${params.FEATURESLOT}:${params.RUNENV} ${env.BUILD_NUMBER}"""
                  catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    sh script: "echo Created by dogefood >> ${params.service}_${params.RUNENV}-${params.FEATURESLOT}"
                    archiveArtifacts "${params.service}_${params.RUNENV}-${params.FEATURESLOT}"
                  }
                }
              }
            }
          }
        }
      }
    }

    stage('Deploy API docs') {
      when {
        expression { params.API_DOCS == 'YES' }
      }
      steps {
        catchError(stageResult: 'FAILURE') {
          container('main') {
            dir("${env.WORKSPACE}/cloud/services/${params.service}/v3.0/src") {
              sh script: "npm run deploy:docs"
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