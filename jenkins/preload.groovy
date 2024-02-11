import groovy.transform.Field

@Field def doNotTestIntListSample = ['serviceName1', 'serviceName2', 'serviceName3']

@Field def doNotTestIntList = ['serviceName3','serviceName4', 'serviceName5']

@Field def servicesInK8s = ['serviceName5', 'serviceName6']

return this;

def deployAllDevStage() {
  script {
    SERVICES = sh(
      script: """jq -r  '.cicd | select(.deployCF==true) or select(.deployPulumi==true) | input_filename' services/*/v3.0/src/package.json | cut -d / -f 2""",
      returnStdout: true
    ).split("\\s+");
    SIZE = SERVICES.size();
    echo "Detected ${SIZE} services to test: ${SERVICES}"
  }
}

def autoDetectPulumifiedServices() {
  script {
    SERVICESPULUMIFIED = sh(
      script: """jq -r  '.cicd | select(.deployPulumi==true) | input_filename' services/*/v3.0/src/package.json | cut -d / -f 2""",
      returnStdout: true
    ).split("\\s+");
    SIZE = SERVICES.size();
    echo "Detected ${SIZE} services to test: ${SERVICESPULUMIFIED}"
  }
}

def autoDetectAllServices() {
  script {
    SERVICES = sh(
      script: """jq -r  '.scripts | select(has("build")) | input_filename' services/*/v3.0/src/package.json | cut -d / -f 2""",
      returnStdout: true
    ).split("\\s+");
    SIZE = SERVICES.size();
    echo "Detected ${SIZE} services to test: ${SERVICES}"
  }
}

def autoDetectK8sServices() {
  script {
    SERVICESK8S = sh(
      script: """jq -r  '.cicd | select(.deployK8s==true) | input_filename' services/*/v3.0/src/package.json | cut -d / -f 2""",
      returnStdout: true
    ).split("\\s+");
    SIZE = SERVICES.size();
    echo "Detected ${SIZE} services to test: ${SERVICESK8S}"
  }
}

def autoDetectCFServicesWithIntTest() {
  script {
    SERVICES = sh(
      script: """jq -r  '.cicd | select(.deployCF==true) and select(.testInt==true) | input_filename' services/*/v3.0/src/package.json | cut -d / -f 2""",
      returnStdout: true
    ).split("\\s+");
    SIZE = SERVICES.size();
    echo "Detected ${SIZE} services to test: ${SERVICES}"
  }
}

def autoDetectServicesForIntTest() {
  script {
    SERVICES = sh(
      script: """jq -r  '.cicd | select(.testInt==true) | input_filename' services/*/v3.0/src/package.json | cut -d / -f 2""",
      returnStdout: true
    ).split("\\s+");
    SIZE = SERVICES.size();
    echo "Detected ${SIZE} services to test: ${SERVICES}"
  }
}

def autoDetectServicesForUnitTest() {
  script {
    SERVICES = sh(
      script: """jq -r  '.cicd | select(.testUnit==true) | input_filename' services/*/v3.0/src/package.json | cut -d / -f 2""",
      returnStdout: true
    ).split("\\s+");
    SIZE = SERVICES.size();
    echo "Detected ${SIZE} services to test: ${SERVICES}"
  }
}

def createHtmlReport() {
  sh """
    TODAY=`date +"%b %d"`
    sed -i "s/%%JOBNAME%%/${env.JOB_NAME}/g" htmloutput/report.html
    sed -i "s/%%BUILDNO%%/${env.BUILD_NUMBER}/g" htmloutput/report.html
    sed -i "s/%%DATE%%/\${TODAY}/g" htmloutput/report.html
    sed -i "s/%%BUILD_STATUS%%/${BUILD_STATUS}/g" htmloutput/report.html
    perl -i -p0e 's/%%ERROR%%/`cat errors.log`/se' htmloutput/report.html
    sed -i "s|%%CONSOLE_LOG%%|${CONSOLE_LOG}|g" htmloutput/report.html
    sed -i "s|%%JOBLINK%%|${JOBLINK}|g" htmloutput/report.html
    perl -i -p0e 's/%%ERROR_MESSAGES%%/`cat error_messages.log`/se' htmloutput/report.html
    """
  publishHTML(target:[
    allowMissing: true,
    alwaysLinkToLastBuild: true,
    keepAll: true,
    reportDir: "htmloutput",
    reportFiles: 'report.html',
    reportName: 'Build-Report',
    reportTitles: 'Build-Report'
  ])
}

def createHtmlReportTest() {
  sh """
    TODAY=`date +"%b %d"`
    sed -i "s/%%JOBNAME%%/${env.JOB_NAME}/g" htmloutput/report.html
    sed -i "s/%%BUILDNO%%/${env.BUILD_NUMBER}/g" htmloutput/report.html
    sed -i "s/%%DATE%%/\${TODAY}/g" htmloutput/report.html
    sed -i "s/%%BUILD_STATUS%%/${BUILD_STATUS}/g" htmloutput/report.html
    perl -i -p0e 's/%%ERROR%%/`cat errors.log`/se' htmloutput/report.html
    sed -i "s|%%CONSOLE_LOG%%|${CONSOLE_LOG}|g" htmloutput/report.html
    sed -i "s|%%JOBLINK%%|${JOBLINK}|g" htmloutput/report.html
    perl -i -p0e 's/%%ERROR_MESSAGES%%/`cat error_messages.log`/se' htmloutput/report.html
    """
  publishHTML(target:[
    allowMissing: true,
    alwaysLinkToLastBuild: true,
    keepAll: true,
    reportDir: "htmloutput",
    reportFiles: 'report.html',
    reportName: 'Build-Report',
    reportTitles: 'Build-Report'
  ])
}

//

def getFailedLogs() {
  dir("${env.WORKSPACE}") {
    script {
      sh """
          echo '<p>$CURRENT_STAGE_NAME</p>' >> errors.log
          cat output.log > failed_error_messages.log
          echo '$CURRENT_STAGE_NAME' >> list_errors.log
          cat failed_error_messages.log | sed 's/.*/&<br>/' > pre_error_messages.log
          echo '<h3>$CURRENT_STAGE_NAME</h3>' >> error_messages.log
          cat pre_error_messages.log >> error_messages.log
          rm pre_error_messages.log
      """
    }
  }
}

def getFailedMochaLogs() {
  dir("${env.WORKSPACE}") {
    script {
      def val = readFile './output.log'
      if (val.contains('failing')) {
        sh """
          echo '<p>$CURRENT_STAGE_NAME</p>' >> errors.log
          echo '$CURRENT_STAGE_NAME' >> list_errors.log
          cat output.log | sed -e '/failing/,\$!d' > failed_error_messages.log
          cat failed_error_messages.log | sed 's/.*/&<br>/' > pre_error_messages.log
          echo '<h3>$CURRENT_STAGE_NAME</h3>' >> error_messages.log
          cat pre_error_messages.log >> error_messages.log
          rm pre_error_messages.log
        """
      } else {
        sh """
          echo 'output.log does not contain the word failing'
          echo '<p>$CURRENT_STAGE_NAME</p>' >> errors.log
          cat output.log > failed_error_messages.log
          echo '$CURRENT_STAGE_NAME' >> list_errors.log
          cat failed_error_messages.log | sed 's/.*/&<br>/' > pre_error_messages.log
          echo '<h3>$CURRENT_STAGE_NAME</h3>' >> error_messages.log
          cat pre_error_messages.log >> error_messages.log
          rm pre_error_messages.log
        """
      }
    }
  }
}

def determineResult() {
  dir("${env.WORKSPACE}") {
    script {
      sh script: "ls -l"
      if (fileExists('errors.log')) {
        list_errors = sh(script: "cat list_errors.log | perl -pe 'if(!eof){s/\n/, /}'", returnStdout: true).toString()
        error()
      } else {
        echo "errors.log not created"
      }
    }
  }
}

def determinePassFail() {
  dir("${env.WORKSPACE}") {
    script {
      sh script: "ls -l"
      if (currentBuild.currentResult != "SUCCESS") {
        list_errors = sh(script: "cat list_errors.log | perl -pe 'if(!eof){s/\n/, /}'", returnStdout: true).toString()
        error()
      } else {
        echo "It's a PASS!"
      }
    }
  }
}

def getMochaErrorLog() {
  dir("${env.WORKSPACE}") {
    script {
      if ( currentBuild.currentResult == "SUCCESS" ) {
      } else {
        CURRENT_STAGE_NAME=env.STAGE_NAME
        sh """
          echo '<p>$CURRENT_STAGE_NAME</p>' >> errors.log
          echo '$CURRENT_STAGE_NAME' >> list_errors.log
          cat output.log | sed -e '/failing/,\$!d' > failed_error_messages.log
          cat failed_error_messages.log | sed 's/.*/&<br>/' > pre_error_messages.log
          echo '<h3>$CURRENT_STAGE_NAME</h3>' >> error_messages.log
          cat pre_error_messages.log >> error_messages.log
          rm pre_error_messages.log
        """
      }
    }
  }
}

def getErrorLog() {
  dir("${env.WORKSPACE}") {
    script {
      if ( currentBuild.currentResult == "SUCCESS" ) {
      } else {
        CURRENT_STAGE_NAME=env.STAGE_NAME
        sh """
          echo '<p>$CURRENT_STAGE_NAME</p>' >> errors.log
          echo '$CURRENT_STAGE_NAME' >> list_errors.log
          cat output.log > failed_error_messages.log
          cat failed_error_messages.log | sed 's/.*/&<br>/' > pre_error_messages.log
          echo '<h3>$CURRENT_STAGE_NAME</h3>' >> error_messages.log
          cat pre_error_messages.log >> error_messages.log
          rm pre_error_messages.log
        """
      }
    }
  }
}

def getLoopMochaErrorLog() {
  dir("${env.WORKSPACE}") {
    script {
      waitUntil {
        if ( currentBuild.currentResult == "SUCCESS" ) {
        } else {
          CURRENT_STAGE_NAME="Test - $service"
          sh """
            echo '<p>$CURRENT_STAGE_NAME</p>' >> errors.log
            echo '$CURRENT_STAGE_NAME' >> list_errors.log
            cat output.log | sed -e '/failing/,\$!d' > failed_error_messages.log
            cat failed_error_messages.log | sed 's/.*/&<br>/' > pre_error_messages.log
            echo '<h3>$CURRENT_STAGE_NAME</h3>' >> error_messages.log
            cat pre_error_messages.log >> error_messages.log
            rm pre_error_messages.log
          """
        }
      }
    }
  }
}

def getLoopErrorLog() {
  dir("${env.WORKSPACE}") {
    script {
      if ( currentBuild.currentResult == "SUCCESS" ) {
      } else {
        sh """
          echo '<p>$CURRENT_STAGE_NAME</p>' >> errors.log
          echo '$CURRENT_STAGE_NAME' >> list_errors.log
          cat output.log > failed_error_messages.log
          cat failed_error_messages.log | sed 's/.*/&<br>/' > pre_error_messages.log
          echo '<h3>$CURRENT_STAGE_NAME</h3>' >> error_messages.log
          cat pre_error_messages.log >> error_messages.log
          rm pre_error_messages.log
        """
      }
    }
  }
}

def sendSlackNotifcation() { 
	if ( currentBuild.currentResult == "SUCCESS" ) {
	}
	else {
		buildSummary = "*FAILED* Job ${env.JOB_NAME}\n Errors: $list_errors\n Build Report : ${env.BUILD_URL}Build-Report"
		slackSend color : "danger", message: "${buildSummary}", channel: "${params.SLACKCHANNEL}"
	}
}

def slackSendBuildsDevStage() { 
	if ( currentBuild.currentResult == "SUCCESS" ) {
	}
	else {
		buildSummary = "*FAILED* Job ${env.JOB_NAME}\n Errors: $list_errors\n Build Report : ${env.BUILD_URL}Build-Report"
		slackSend color : "danger", message: "${buildSummary}", channel: '#builds-dev-stage'
	}
}

def slackSendBuildsProd() { 
	if ( currentBuild.currentResult == "SUCCESS" ) {
	}
	else {
		buildSummary = "*FAILED* Job ${env.JOB_NAME}\n Errors: $list_errors\n Build Report : ${env.BUILD_URL}Build-Report"
		slackSend color : "danger", message: "${buildSummary}", channel: '#builds-prod'
	}
}

def slackSendCloudBuild() { 
	if ( currentBuild.currentResult == "SUCCESS" ) {
	}
	else {
		buildSummary = "*FAILED* Job ${env.JOB_NAME}\n Errors: $list_errors\n Build Report : ${env.BUILD_URL}Build-Report"
		slackSend color : "danger", message: "${buildSummary}", channel: '#builds-dev-stage'
	}
}

def slackSendTestAutomation() { 
	if ( currentBuild.currentResult == "SUCCESS" ) {
	}
	else {
		buildSummary = "*FAILED* Job ${env.JOB_NAME}\n Errors: $list_errors\n Build Report : ${env.BUILD_URL}Build-Report"
		slackSend color : "danger", message: "${buildSummary}", channel: '#test-automation'
	}
}

def slackSendMinspresso() { 
	if ( currentBuild.currentResult == "SUCCESS" ) {
	}
	else {
		buildSummary = "*FAILED* Job ${env.JOB_NAME}\n Errors: $list_errors\n Build Report : ${env.BUILD_URL}Build-Report"
		slackSend color : "danger", message: "${buildSummary}", channel: "@minspresso"
	}
}

return this