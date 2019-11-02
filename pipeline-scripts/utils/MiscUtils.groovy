#!groovy

package com.cisco.ccone.JenkinsUtils

import groovy.json.JsonSlurper

/*Method to get Build Command */
@NonCPS
def getBuildCommand(modulesCommandMap,module)
{
	def buildCommand = "mvn clean install -Dmaven.test.skip=true"
	if(modulesCommandMap[module])
	{
		def command = modulesCommandMap[module]
		buildCommand = "ant $command"
		return buildCommand

	}
	return buildCommand
}

/**
 * This script has utility functions
 * Methods defined are used by PR builder
 */
 
 /*
 *Validate modules to be build
 */
 def validateModules(modulesChanged,serviceModule)  
{
	echo "changedModules : $modulesChanged"
	echo "serviceModule : $serviceModule"
	def serviceModuleChanged = []
	def isCrossSGModule = false
		
	for(module in modulesChanged)
	{
		
		for (services in serviceModule)
		{
			if(module.contains(services)) 
			{
				serviceModuleChanged.add(services)
				isCrossSGModule = false
				break;
			}
			else
			{
				isCrossSGModule = true
			}
		}
		if(isCrossSGModule)
		{
			echo "Cross Service Group PR"
			throw new Exception("PR contains changes in other Service Group")
		}
					
	}
	return serviceModuleChanged.toSet()
}

// Method to get changeSet using git diff 
def getChangeSet(branchCommit,masterCommit)
{
	def changeSets = sh( script: "git diff --name-only ${branchCommit} ${masterCommit}", returnStdout: true )
	changeSets = changeSets.replaceAll('^\\s+',"");
	String[] changedFileSets = changeSets.split("\\s+");

	return changedFileSets
}

/*Method to identify diffrence between Sonar analysis */
@NonCPS
def sonarDelta(metric,preScanValue, postScanValue)
{
	def result = true
	if (metric == "vulnerabilities")
	{
		result = preScanValue >= postScanValue
		return result
	}
	if (metric == "coverage")
	{
		result = preScanValue <= postScanValue
		return result
	}
	return result
}

/* Method to get Sonar metrics details*/
def getSonarMetrics(jsonResponse)
{
	String metrics = " "
	def sonarJson = readJSON text: "$jsonResponse"
	def projectName = sonarJson.component.name
	println("Project Name : "+projectName)
	def measures = sonarJson.component.measures
	for (measure in measures)
	{
		metrics = metrics + measure.metric + ":"+ measure.value + ","
	}
	return metrics
}

/*Method to get Sonar Branch Name */
@NonCPS
def getSonarBranchName(branchName)
{
	
	if(branchName != "master")
	{
		return "branch-${branchName}"
		
	}
	return branchName
}

/*
 * Method to identify the modules changed 
 */
@NonCPS
def getModifiedModules(files)
{
	def modulesChanged = []
	for (file in files) 
	{
		echo "file : ${file}"
		def tokens = file.split('/')
		def tokensLength = tokens.length
		if(tokensLength == 1)
		{
			def parentFolder = tokens[0]
			modulesChanged.add(parentFolder)
		}
		else
		{
			def parentFolder = tokens[0]
		        def childFolder = tokens[1]
		        def folder = "${parentFolder}/${childFolder}"
		        modulesChanged.add(folder)
		}
								
	}	
	return modulesChanged.toSet()
}

/*
 * Method to merge pull request to master branch 
 */
def mergePullRequest() 
{
    step([$class: 'GhprbPullRequestMerge', allowMergeWithoutTriggerPhrase: false, deleteOnMerge: true,
          disallowOwnCode: false, failOnNonMerge: true, mergeComment: 'Merged', onlyAdminsMerge: false])

}


/*
 * Converts String Json into Map Object
 */
@NonCPS
def stringToMap(stringJson)
{
	stringJson = stringJson.substring(1, stringJson.length()-1)
	String[] keyValuePairs = stringJson.split(",");  		
	Map<String,String> map = new HashMap<>();               

	for(String pair : keyValuePairs)                        
	{
		String[] entry = pair.split(":");                   
		map.put(entry[0].trim(), entry[1].trim());
	}
	return map
}

// To check Validation comment issued or not
@NonCPS
def isValidateCommentIssued(content, validateComment)
{
	def commentIssuedStatus = false
	def jsonSlurper = new JsonSlurper()
	def resultJson = jsonSlurper.parseText(content)
	for(results in resultJson)
	{
		comment = results.body
		if(comment == validateComment)
		{
			commentIssuedStatus = true
			echo "commentIssuedStatus : commentIssuedStatus"
			break ;
		}
	}
	return commentIssuedStatus
}

/*
* Gets value if keys exists in Map
*/
@NonCPS
def getValueFromMap(dictionary,key)
{
	if(dictionary[key])
	{
		return dictionary[key]
	}
	return key
}

/*
 * Gets host for specified module
 */
@NonCPS
def getConsulAddress(host)
	{
		hostName = host.split('\\.')
		delimiter = "-"
		suffix = ".node.consul"
		installerHost = "${hostName[2]}${delimiter}${hostName[0]}${suffix}"
		return installerHost
	}

/*
 * Gets build path for specified module
 */
@NonCPS
def getBuildPath(pathMap,module)
{
	
	if(pathMap[module])
	{
		return pathMap[module]
	}
	else
	{
		return module
	}
}

/*
 * Removes child modules
 */
@NonCPS
def removeChildModules(currentModules,dependency)
{
	def updatedModules = currentModules
	def dependencyMap = stringToMap(dependency)
	for (module in currentModules)
	{

		if(dependencyMap[module])
		{
			echo "Dependency exists for $module"
			
			if(currentModules.contains(dependencyMap[module]))
			{
				echo "Removing child module $module \nParent module exists in Modified Modules"
				updatedModules.remove(module)
			}
		}
		else
		{
			echo "No dependency found for $module"
		}
	}
	echo "updatedModules : $updatedModules"
	return updatedModules
}

/*
 * Add dependent module
 */
@NonCPS
def addDependentModules(currentModules,dependency)
{
	def updatedModules = currentModules
	def dependencyMap = stringToMap(dependency)
	for (module in currentModules)
	{
		if(dependencyMap[module])
		{
			dependentModule = dependencyMap[module]
			echo "Dependency exists for $module"
			echo "Removing module $module"
			updatedModules.remove(module)
			if(updatedModules.contains(dependentModule))
			{
				echo "$dependentModule already exists in modules list"
			}
			else
			{
				echo "Adding module $dependentModule"
				updatedModules.add(dependentModule)
			}
			
		}
		else
		{
			echo "No dependency found for $module"
		}
	}
	echo "updatedModules : $updatedModules"
	return updatedModules
}


/*
 * Gets tar package path
 */
@NonCPS
def getTarPath(tarPathMap,module)
{
	if(tarPathMap[module])
	{
		return tarPathMap[module]
	}
	else
	{
		def suffix = "/target/"
		def path = "${module}${suffix}"
		return path
	}
}

/** Method to get number from String */
def extractInts(input)
{
	return input.replaceAll("[^0-9]", "")
}

	

//Function to copy the package to installer,untar the package and remove the .tar file
def copyPackageToHost(packageName,SSH_USER_NAME,DEPLOY_HOST) {
String buildNum = currentBuild.number.toString()
	withCredentials([string(credentialsId: 'SSH-DEPLOY', variable: 'Jenkinspass')]) {
        sh """
            #!/bin/bash
			sshpass -p $Jenkinspass ssh $SSH_USER_NAME@$DEPLOY_HOST
			
			sshpass -p $Jenkinspass scp -r -v -o 'StrictHostKeyChecking no' $WORKSPACE/${packageName}/target/libs-snapshot-local/${packageName}-b${buildNum}.tar $SSH_USER_NAME@$DEPLOY_HOST:~/apache-tomcat-8.5.47/webapps/ 
			
			sshpass -p $Jenkinspass ssh $SSH_USER_NAME@$DEPLOY_HOST tar -xvf ~/apache-tomcat-8.5.47/webapps/${packageName}-b${buildNum}.tar --directory ~/apache-tomcat-8.5.47/webapps; rm -rf ~/apache-tomcat-8.5.43/webapps/${packageName}-b${buildNum}.tar
			
			sshpass -p $Jenkinspass ssh $SSH_USER_NAME@$DEPLOY_HOST screen -dm -S testing "~/apache-tomcat-8.5.47/webapps/./script.sh ${packageName}"
			
			[ \$? -ne 0 ] && exit 1
			
            exit 0
        """
    }
}


//Function to get the current version of the package in the respective host
def getCurrentVersion(packageName,SSH_USER_NAME,BASTION_HOST,INSTALLER_HOST,hostName) 
{
    withCredentials([file(credentialsId: 'devus1-ubuntu-pem', variable: 'jenkinsKeyFile')]) 
	{
        return sh(script: """
            #!/bin/bash
            VERSION=\$(ssh -i $jenkinsKeyFile -o StrictHostKeyChecking=no $SSH_USER_NAME@$BASTION_HOST ssh -i $jenkinsKeyFile -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no $SSH_USER_NAME@$INSTALLER_HOST bash -c "'
                find /opt/serverconfig/$hostName/ -name host.properties -exec grep "${packageName}-.*.version" {} \\; | cut -f2 -d'=' | head -1
            '")
            echo \$VERSION
        """, returnStdout: true)
    }
}
//Function to remove the package on installer, after published to artifactory
def validatePackageForDeletion(packageName,SSH_USER_NAME,BASTION_HOST,INSTALLER_HOST) {
    withCredentials([file(credentialsId: 'devus1-ubuntu-pem', variable: 'jenkinsKeyFile')]) 
	{
        return sh(script: """
			#!/bin/bash
			PACKAGELIST=\$(ssh -i $jenkinsKeyFile -o StrictHostKeyChecking=no $SSH_USER_NAME@$BASTION_HOST ssh -i $jenkinsKeyFile -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no $SSH_USER_NAME@$INSTALLER_HOST bash -c "'
				find /opt/serverconfig/ -name host.properties -exec grep "${packageName}-.*.version" {} \\; | cut -f2 -d'='
			'")
			echo \$PACKAGELIST
		""", returnStdout: true)
    }
}


//Function to remove the package on installer, after published to artifactory
def removePackageOnInstaller(packageName,previousInstalledVersion,SSH_USER_NAME,BASTION_HOST,INSTALLER_HOST) {
    withCredentials([file(credentialsId: 'devus1-ubuntu-pem', variable: 'jenkinsKeyFile')]) {
        sh """
            #!/bin/bash
			ssh -i $jenkinsKeyFile -o StrictHostKeyChecking=no $SSH_USER_NAME@$BASTION_HOST
			ssh -i $jenkinsKeyFile $SSH_USER_NAME@$INSTALLER_HOST -o StrictHostKeyChecking=no -o "proxycommand ssh -W %h:%p -i $jenkinsKeyFile $SSH_USER_NAME@$BASTION_HOST" \
			"sudo rm -rf /usr/local/lib/xera/packages/${packageName}/${previousInstalledVersion}/"
			[ \$? -ne 0 ] && exit 1
            exit 0
        """
    }
}

/** Method to display build */
def setDisplayName(buildNum, appBase) {
    currentBuild.displayName = '#' + buildNum + ' ' + appBase
}

// To send email
def sendEmail(status,stageName,checkpoint)
{
		emailext (
					mimeType: 'text/html',
					subject: "${status}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
					body: """<p>${status}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' at stage ${stageName}</p>
					<p>Check console output at ${env.BUILD_URL}</p>
					<p>Trigger job using checkpoint '${checkpoint}'</p>""",
					to: "${ghprbTriggerAuthor}@cisco.com"
				  )

}

return this;
