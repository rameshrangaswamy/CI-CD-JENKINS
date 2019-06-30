#!groovy

/** provide comment as TRIALTEST to trigger this job */

/** Specifying node on which current build would run */

node("NODE_LABEL") 

{

def mavenHome = tool 'maven'

def stageName

def commitHash 

def currentModules

def gitCommit

String buildNum = currentBuild.number.toString()

//def buildInfo

def Logger

def packageName

def moduleTarPath

def server = Artifactory.server "ArtifactDemo"

def buildInfo = Artifactory.newBuildInfo()

def SSH_USER_NAME

def DEPLOY_HOST

	
	stage('Git clone and setup')
	{
		try 
		{
			stageName = "Git clone and setup"
			
			checkout scm
			
			def currentDir
			
			currentDir = pwd()
			
			Logger = load("${currentDir}/pipeline-scripts/utils/Logger.groovy")
			
			Logger.info("Entering Git clone and setup stage")
			
			def moduleProp = readProperties file: 'pipeline-scripts/properties/modules.properties'

			MiscUtils = load("${currentDir}/pipeline-scripts/utils/MiscUtils.groovy")
			
			println("Reading modules.properties : $moduleProp")
			
			// Get the commit hash of PR branch 
			
			def branchCommit = sh( script: "git rev-parse refs/remotes/${sha1}^{commit}", returnStdout: true )
			
			// Get the commit hash of Master branch
			
			def masterCommit = sh( script: "git rev-parse origin/${ghprbTargetBranch}^{commit}", returnStdout: true )
			
			commitHash =  sh( script: "git rev-parse origin/${env.GIT_BRANCH}",returnStdout: true, )
			
			commitHash = commitHash.replaceAll("[\n\r]", "")
			
			branchCommit = branchCommit.replaceAll("[\n\r]", "")
			
			masterCommit = masterCommit.replaceAll("[\n\r]", "")
			
			println("branchCommit : $branchCommit")
			
			println("masterCommit : $masterCommit")
			
			def changeSet = MiscUtils.getChangeSet(branchCommit,masterCommit)
			
			def changedModules = MiscUtils.getModifiedModules(changeSet)
			
			def serviceModules = moduleProp['DEMO_MODULES']
			
			def serviceModulesList = serviceModules.split(',')
			
			currentModules = MiscUtils.validateModules(changedModules,serviceModulesList)
			
			println("Service modules changed : $currentModules")	
			
			MiscUtils.setDisplayName(buildNum, currentModules)
		}
			catch(Exception exception) 
		{
			currentBuild.result = "FAILURE"
			
			Logger.error("Git clone and setup failed : $exception")
			
			throw exception
		}
		finally
		{
			Logger.info("Exiting Git clone and setup stage")
		}
	}
		
	stage('Build')
	{    
		try
		{
			def currentDir
			
			currentDir = pwd()
			
		    Logger = load("${currentDir}/pipeline-scripts/utils/Logger.groovy")
			
			Logger.info("Entering Build stage")
			
			for(module in currentModules)
			{
				def moduleProp = readProperties file: 'pipeline-scripts/properties/modules.properties'
				
				def packagePath = moduleProp['DEMO_PACKAGEPATH']
				
				println("packagePath : $packagePath")
				
				packagePathMap = MiscUtils.stringToMap(packagePath)
				
				println("packagePathMap : $packagePathMap")
				
				def packageBuildPath = MiscUtils.getBuildPath(packagePathMap,module)
				
				//def command = MiscUtils.getBuildCommand(buildCommandMap,module)
				
				dir(packageBuildPath)
				{
					sh "'${mavenHome}/bin/mvn' clean install -Dmaven.test.skip=true"
				}
			}
		}
				catch(Exception exception) 
			{
				currentBuild.result = "FAILURE"
				
				Logger.error("Build failed : $exception")
				
				throw exception
			}
			finally
			{
				Logger.info("Exiting Build stage")
			}
	}
	
	stage('UTs')
	{    
		try
		{
			def currentDir
			
			currentDir = pwd()
			
		    Logger = load("${currentDir}/pipeline-scripts/utils/Logger.groovy")
			
			Logger.info("Entering UTs stage")
			
			for(module in currentModules)
			{
				def moduleProp = readProperties file: 'pipeline-scripts/properties/modules.properties'
				
				def packagePath = moduleProp['DEMO_PACKAGEPATH']
				
				packagePathMap = MiscUtils.stringToMap(packagePath)
						
				def packageBuildPath = MiscUtils.getBuildPath(packagePathMap,module)
				
				dir(packageBuildPath)
				{
					sh "'${mavenHome}/bin/mvn' test"
				}
			}
		}
				catch(Exception exception) 
			{
				currentBuild.result = "FAILURE"
				
				Logger.error("UTs failed : $exception")
				
				throw exception
			}
			finally
			{
				Logger.info("Exiting UTs stage")
			}
	}
	
	stage('sonarAnalysis')
	{    
		try
		{		
			def currentDir
			
			currentDir = pwd()
			
			Logger = load("${currentDir}/pipeline-scripts/utils/Logger.groovy")
			
			Logger.info("Entering SonarAnalysis stage")
			
			for(module in currentModules)
			{
				def moduleProp = readProperties file: 'pipeline-scripts/properties/modules.properties'
				
				def packagePath = moduleProp['DEMO_PACKAGEPATH']
						
				packagePathMap = MiscUtils.stringToMap(packagePath)
				
				def sonarBranchName = MiscUtils.getSonarBranchName(ghprbSourceBranch)
				
				def packageBuildPath = MiscUtils.getBuildPath(packagePathMap,module)
				
				//def command = MiscUtils.getBuildCommand(buildCommandMap,module)
				
				dir(packageBuildPath)
				{
					withSonarQubeEnv('SonarDemo')
					{
						//sh "'${mavenHome}/bin/mvn' sonar:sonar"
						sh "${mavenHome}/bin/mvn -Dsonar.branch.name=${sonarBranchName} sonar:sonar"
						//-Dsonar.host.url=http://35.200.203.119:9000 \
						//-Dsonar.login=bc7ed6c23eabd5e5001bcc733194bf9925c85efc"
					}
			
					Logger.info("Waiting for SonarQube Quality evaluation response")
					
					timeout(time: 1, unit: 'HOURS')
					{
						// Wait for SonarQube analysis to be completed and return quality gate status
						
						def quality = waitForQualityGate()
						
						if(quality.status != 'OK')
						{
							println("Quality gate check failed")
							
							throw new Exception("Quality Gate check failed")
						}
					}
				}
			}
		}
				catch(Exception exception) 
			{
				currentBuild.result = "FAILURE"
				
				Logger.error("Quality gate failed: $exception")
				
				throw exception
			}
			finally
			{
				Logger.info("Exiting SonarAnalysis stage")
			}
	}
	
	stage('Packaging And Archiving') 
	{
	
		try
		{
				
				def currentDir

				currentDir = pwd()
				
				stageName = "Packaging And Archiving"

				Logger = load("${currentDir}/pipeline-scripts/utils/Logger.groovy")
				
				Logger.info("Entering stage Publish to Artifactory")
							
				MiscUtils = load("${currentDir}/pipeline-scripts/utils/MiscUtils.groovy")
				
				moduleProp = readProperties file: 'pipeline-scripts/properties/modules.properties'	
				
				commitHash =  sh( script: "git rev-parse origin/${env.GIT_BRANCH}",returnStdout: true, )
				
				gitCommit = commitHash.substring(0,7)
				
				def packageNames = moduleProp['PACKAGE_NAME']
				
				packageMap = MiscUtils.stringToMap(packageNames)
				
				tarPath = moduleProp['TAR_PATH']
				
				def tarPathMap = MiscUtils.stringToMap(tarPath)
							
					for(module in currentModules) 
					{
							packageName = MiscUtils.getValueFromMap(packageMap,module)
							
							moduleTarPath = MiscUtils.getTarPath(tarPathMap,module)	
							
							println("packageName : $packageName")
							
							dir(moduleTarPath)
							{
								sh"""
								#!/bin/bash
								tar cvf "${packageName}-b${buildNum}.tar" *
								"""
							}

					}
		}
			catch(Exception exception) 
			{
				currentBuild.result = "FAILURE"
				
				Logger.error("Packaging And Archiving : $exception")
				
				throw exception
			}
			finally
			{
				Logger.info("Exiting Packaging And Archiving")
			}
	}
			
	stage('Publish to Artifactory') 
	{
	
		try
		{
				def currentDir

				currentDir = pwd()
				
				stageName = "Publish to artifactory"
				
				Logger = load("${currentDir}/pipeline-scripts/utils/Logger.groovy")
				
				Logger.info("Entering stage Publish to Artifactory")
			
				ArtifactoryUtils = load("${currentDir}/pipeline-scripts/utils/ArtifactoryUtils.groovy")
				
				PipeConstants = load("${currentDir}/pipeline-scripts/utils/PipeConstants.groovy")
				
				MiscUtils = load("${currentDir}/pipeline-scripts/utils/MiscUtils.groovy")
				
				moduleProp = readProperties file: 'pipeline-scripts/properties/modules.properties'	
								
				def packageNames = moduleProp['PACKAGE_NAME']
				
				packageMap = MiscUtils.stringToMap(packageNames)
				
				tarPath = moduleProp['TAR_PATH']
				
				def tarPathMap = MiscUtils.stringToMap(tarPath)
							
				for(module in currentModules) 
				{
					packageName = MiscUtils.getValueFromMap(packageMap,module)
					
					moduleTarPath = MiscUtils.getTarPath(tarPathMap,module)	
					
					Logger.info("packageName : $packageName")
					
					dir(moduleTarPath)
					{
								def rtMaven = Artifactory.newMavenBuild()
								buildInfo.env.capture = true
								buildInfo.env.collect()

						script
						{						
							Logger.info("packageName : $packageName")
							
							rtMaven.tool = 'maven'
							
							rtMaven.deployer server: server, releaseRepo: 'libs-release-local', snapshotRepo: 'libs-snapshot-local'
													
							//def buildInfo = Artifactory.newBuildInfo()
							
							//buildInfo.env.capture = true
								
							def uploadSpec = """{
											"files": [{
											"pattern": "${WORKSPACE}/${moduleTarPath}/*.tar",
											"target": "libs-snapshot-local/",
											"recursive": "false"
												  }]
											}"""
							server.upload spec: uploadSpec, buildInfo: buildInfo 
							
							server.publishBuildInfo buildInfo
							
						}
							
					}
	
				}
		}
				
		
	
		
				catch(Exception exception) 
			{
				currentBuild.result = "FAILURE"
				Logger.error("Publish to Artifactory : $exception")
				throw exception
			}
			finally
			{
				Logger.info("Exiting Publish to Artifactory stage")
			}
	} 

		stage('Deployment')
	{
		try
		{
				def currentDir

				currentDir = pwd()

				Logger = load("${currentDir}/pipeline-scripts/utils/Logger.groovy")
			
				Logger.info("Entering Deployment stage")
						
				//ArtifactoryUtils = load("${currentDir}/pipeline-scripts/utils/ArtifactoryUtils.groovy")
				
				//PipeConstants = load("${currentDir}/pipeline-scripts/utils/PipeConstants.groovy")
				
				MiscUtils = load("${currentDir}/pipeline-scripts/utils/MiscUtils.groovy")
				
				moduleProp = readProperties file: 'pipeline-scripts/properties/modules.properties'	
				
				SSH_USER_NAME  = moduleProp['SSH_USER_NAME']	
				
				DEPLOY_HOST = moduleProp['DEPLOY_HOST']
				
				def packageNames = moduleProp['PACKAGE_NAME']
				
				packageMap = MiscUtils.stringToMap(packageNames)
				
				tarPath = moduleProp['TAR_PATH']
				
				def tarPathMap = MiscUtils.stringToMap(tarPath)
				
							for(module in currentModules) 
							{
								packageName = MiscUtils.getValueFromMap(packageMap,module)
								
								moduleTarPath = MiscUtils.getTarPath(tarPathMap,module)	
								
								println("packageName : $packageName")
								
							dir(moduleTarPath)
							{
							
								copyPackageToInstaller(packageName,SSH_USER_NAME,DEPLOY_HOST)
						
							}
						}
		}
				catch(Exception exception) 
			{
				currentBuild.result = "FAILURE"
				Logger.error("Deployment : $exception")
				throw exception
			}
			finally
			{
				Logger.info("Exiting Deployment stage")
			}
	}
	
}

//Function to copy the package to installer,untar the package and remove the .tar file
def copyPackageToInstaller(packageName,SSH_USER_NAME,DEPLOY_HOST) {
	withCredentials([string(credentialsId: 'artifact-machine', variable: 'Jenkinspass')]) {
        sh """
            #!/bin/bash
			ssh -i $Jenkinspass -o StrictHostKeyChecking=no $SSH_USER_NAME@$DEPLOY_HOST
			
			
			scp -i $Jenkinspass -o StrictHostKeyChecking=no -o "proxycommand ssh -i $Jenkinspass -W %h:%p $SSH_USER_NAME@$DEPLOY_HOST" \
			
			${packageName}*.tar $SSH_USER_NAME@$DEPLOY_HOST:~/apache-tomcat-8.5.42/webapps/${packageName}.tar
			
			[ \$? -ne 0 ] && exit 1
			
			ssh -i $Jenkinspass $SSH_USER_NAME@$DEPLOY_HOST -o StrictHostKeyChecking=no -o "proxycommand ssh -W %h:%p -i $Jenkinspass $SSH_USER_NAME@$DEPLOY_HOST" \
			
			"sudo tar -xvf ~/apache-tomcat-8.5.42/webapps/${packageName}.tar --directory ~/apache-tomcat-8.5.42/webapps/${packageName}.tar; \
			
			rm ~/apache-tomcat-8.5.42/webapps/${packageName}.tar"
			
			[ \$? -ne 0 ] && exit 1
            exit 0
        """
    }
}
